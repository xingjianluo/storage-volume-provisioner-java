package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimView;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolumeClaim;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.ClaimStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 卷请求的供给、匹配与绑定。
 *
 * <p>绑定在“候选卷自身的锁”内做 check-then-set（{@link PersistentVolume#tryBind}），
 * 保证并发下同一块卷绝不重复绑定；若预留卷后发现请求已被他人绑定，
 * 则通过 {@link PersistentVolume#rollbackBind} 补偿退回。</p>
 *
 * <p>不满足条件的请求留在 PENDING，并按固定优先级给出唯一一个可区分原因：
 * 存储类 &gt; 容量 &gt; 访问模式；连候选卷都没有则报 NO_VOLUME_AVAILABLE。</p>
 */
@Service
public class BindingService {

    private static final Logger log = LoggerFactory.getLogger(BindingService.class);

    private final VolumeStore volumeStore;
    private final ClaimStore claimStore;

    public BindingService(VolumeStore volumeStore, ClaimStore claimStore) {
        this.volumeStore = volumeStore;
        this.claimStore = claimStore;
    }

    public ClaimView createClaim(ClaimRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "请求名称不能为空");
        }
        long requested = request.capacityBytes() == null ? -1L : request.capacityBytes();
        if (requested <= 0) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "capacityBytes 必须为正数");
        }
        EnumSet<AccessMode> modes = RequestParsers.parseAccessModes(request.accessModes());
        String storageClass = RequestParsers.normalizeStorageClass(request.storageClassName());

        PersistentVolumeClaim claim =
                new PersistentVolumeClaim(request.name(), requested, modes, storageClass);
        claimStore.put(claim);
        log.info("创建卷请求: name={}, requestedCapacity={}, accessModes={}, storageClass={}, state=PENDING",
                request.name(), requested, modes, storageClass);

        // 创建即尝试一次匹配；失败也保留 PENDING，原因已记录
        tryBindOnce(claim);
        return ClaimView.of(claim);
    }

    /** 尝试把一个待处理请求绑定到满足条件的卷；失败则留在 PENDING 并记录原因。 */
    public OperationResponse bind(String claimName) {
        PersistentVolumeClaim claim = claimStore.get(claimName);
        synchronized (claim) {
            if (claim.getState() == com.github.xingjianluo.storagevolumeprovisionerjava.model.ClaimState.BOUND) {
                log.info("绑定请求被拒绝: claim={} 已绑定到卷 {}", claimName, claim.getVolumeRef());
                throw new ProvisioningException(ReasonCode.CLAIM_ALREADY_BOUND,
                        "请求 " + claimName + " 已绑定到卷 " + claim.getVolumeRef());
            }
            if (claim.getState() == com.github.xingjianluo.storagevolumeprovisionerjava.model.ClaimState.LOST) {
                throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                        "请求 " + claimName + " 处于 LOST，不能重新绑定");
            }
        }
        boolean bound = tryBindOnce(claim);
        ClaimView view = ClaimView.of(claim);
        if (bound) {
            return OperationResponse.ok("BOUND",
                    "请求 " + claimName + " 已绑定到卷 " + claim.getVolumeRef(), view);
        }
        return new OperationResponse(false, "PENDING",
                "请求 " + claimName + " 仍待处理：" + claim.getPendingReason(), view);
    }

    /** 后台语义：尝试绑定所有 PENDING 请求，返回逐条结果。 */
    public List<OperationResponse> reconcileAll() {
        List<OperationResponse> results = new ArrayList<>();
        for (PersistentVolumeClaim claim : claimStore.findAll()) {
            if (claim.getState() != com.github.xingjianluo.storagevolumeprovisionerjava.model.ClaimState.PENDING) {
                continue;
            }
            boolean bound = tryBindOnce(claim);
            ClaimView view = ClaimView.of(claim);
            if (bound) {
                results.add(OperationResponse.ok("BOUND",
                        "请求 " + claim.getName() + " 已绑定到卷 " + claim.getVolumeRef(), view));
            } else {
                results.add(new OperationResponse(false, "PENDING",
                        "请求 " + claim.getName() + " 仍待处理：" + claim.getPendingReason(), view));
            }
        }
        return results;
    }

    public ClaimView getClaim(String name) {
        return ClaimView.of(claimStore.get(name));
    }

    public List<ClaimView> listClaims() {
        return claimStore.findAll().stream().map(ClaimView::of).toList();
    }

    /**
     * 一次匹配尝试。候选 = 所有 AVAILABLE 卷（名称排序，顺序确定）。
     * 评估每块卷的匹配情况，并按优先级汇总唯一失败原因。
     */
    private boolean tryBindOnce(PersistentVolumeClaim claim) {
        List<PersistentVolume> candidates = volumeStore.findAll().stream()
                .filter(v -> v.getState() == VolumeState.AVAILABLE)
                .toList();

        log.info("绑定评估开始: claim={}, 要求 capacity>={}, accessModes={}, storageClass={}, 候选卷数={}",
                claim.getName(), claim.getRequestedCapacityBytes(), claim.getAccessModes(),
                claim.getStorageClassName(), candidates.size());

        if (candidates.isEmpty()) {
            recordPending(claim, ReasonCode.NO_VOLUME_AVAILABLE, "没有任何 AVAILABLE 卷");
            return false;
        }

        // 每块候选卷不满足的首要原因；只要存在完全匹配的卷即可绑定
        PersistentVolume winner = null;
        ReasonCode aggregateReason = null;
        String aggregateDetail = null;
        // 优先级：存储类 > 容量 > 访问模式
        for (PersistentVolume v : candidates) {
            ReasonCode r = evaluate(v, claim);
            if (r == null) {
                winner = v;
                break;
            }
            log.info("候选卷不匹配: claim={}, volume={}, reason={}", claim.getName(), v.getName(), r);
            if (aggregateReason == null || priority(r) < priority(aggregateReason)) {
                aggregateReason = r;
                aggregateDetail = v.getName() + " -> " + r.getDefaultMessage();
            } else {
                aggregateDetail = aggregateDetail + "; " + v.getName() + " -> " + r.getDefaultMessage();
            }
        }

        if (winner == null) {
            recordPending(claim, aggregateReason, aggregateDetail);
            return false;
        }

        return commitBinding(winner, claim);
    }

    /** 返回 null 表示完全匹配；否则返回首要不满足原因。 */
    private ReasonCode evaluate(PersistentVolume volume, PersistentVolumeClaim claim) {
        if (!Objects.equals(volume.getStorageClassName(), claim.getStorageClassName())) {
            return ReasonCode.STORAGE_CLASS_NOT_MATCHED;
        }
        if (volume.getCapacityBytes() < claim.getRequestedCapacityBytes()) {
            return ReasonCode.CAPACITY_NOT_SATISFIED;
        }
        if (!volume.getAccessModes().containsAll(claim.getAccessModes())) {
            return ReasonCode.ACCESS_MODE_NOT_SATISFIED;
        }
        return null;
    }

    private int priority(ReasonCode code) {
        return switch (code) {
            case STORAGE_CLASS_NOT_MATCHED -> 0;
            case CAPACITY_NOT_SATISFIED -> 1;
            case ACCESS_MODE_NOT_SATISFIED -> 2;
            default -> 3;
        };
    }

    /**
     * 提交绑定：先在卷锁内预留（AVAILABLE 才能成功，天然防重复绑定），
     * 再在 claim 锁内确认仍为 PENDING；若 claim 已被他人绑定则补偿回滚卷。
     */
    private boolean commitBinding(PersistentVolume volume, PersistentVolumeClaim claim) {
        if (!volume.tryBind(claim.getName())) {
            // 并发下被别的请求抢走，本次视为无可用卷，等待下一轮
            log.info("卷预留失败(并发): claim={}, volume={} 已不是 AVAILABLE", claim.getName(), volume.getName());
            recordPending(claim, ReasonCode.NO_VOLUME_AVAILABLE,
                    "候选卷 " + volume.getName() + " 在并发中已被其他请求绑定");
            return false;
        }
        if (!claim.tryBind(volume.getName())) {
            // claim 在评估期间已被并发绑定：必须把卷退回，绝不产生重复/悬挂绑定
            volume.rollbackBind(claim.getName());
            log.info("请求已被并发绑定，回滚卷预留: claim={}, volume={}", claim.getName(), volume.getName());
            throw new ProvisioningException(ReasonCode.CLAIM_ALREADY_BOUND,
                    "请求 " + claim.getName() + " 在并发绑定中已绑定到其他卷");
        }
        log.info("绑定成功: claim={} -> volume={}, capacity={}, accessModes={}, storageClass={}",
                claim.getName(), volume.getName(), volume.getCapacityBytes(),
                volume.getAccessModes(), volume.getStorageClassName());
        return true;
    }

    private void recordPending(PersistentVolumeClaim claim, ReasonCode reason, String detail) {
        claim.markPending(reason, detail);
        log.info("保持待处理: claim={}, state=PENDING, reason={}, detail={}",
                claim.getName(), reason, detail);
    }
}
