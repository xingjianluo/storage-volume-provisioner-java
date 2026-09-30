package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestPhase;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * 供给与绑定服务：接收卷与请求，按容量、访问模式、存储类匹配并绑定。
 *
 * <p>绑定规则（判定依据，按顺序短路）：
 * <ol>
 *   <li>卷必须存在且为 {@link VolumeState#AVAILABLE}；</li>
 *   <li>容量：{@code volume.capacity >= request.capacity}；</li>
 *   <li>访问模式：请求声明的每个模式都必须被卷支持（请求是工作负载的必要条件集合）；</li>
 *   <li>存储类：二者相等（null/空统一按 "default"）。</li>
 * </ol>
 *
 * <p>自动挑选时，PENDING 原因按"最贴近匹配失败"的顺序给出可区分代码：
 * 没有任何卷 -> NO_VOLUMES；存储类全不匹配 -> STORAGE_CLASS_NOT_MATCHED；
 * 容量全不足 -> INSUFFICIENT_CAPACITY；模式不支持 -> ACCESS_MODE_NOT_SUPPORTED；
 * 条件都满足但全部已绑定 -> ALL_MATCHING_VOLUMES_BOUND。
 * 自动挑选永不抛错，不满足就保留 PENDING 并写原因，供调用方解释。
 */
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    public static final String DEFAULT_STORAGE_CLASS = "default";

    private final VolumeRegistry registry;
    private final LocalStorageBackend storageBackend;

    public ProvisioningService(VolumeRegistry registry, LocalStorageBackend storageBackend) {
        this.registry = registry;
        this.storageBackend = storageBackend;
    }

    /** 注册一个新供给的卷，并在后端建立空数据块。 */
    public PersistentVolume registerVolume(PersistentVolume volume) {
        validateVolume(volume);
        registry.registerVolume(volume);
        storageBackend.initData(volume.getName());
        volume.setLastOperation("registered");
        log.info("register-volume input={} decision=registered state=AVAILABLE basis=capacity+modes+class+policy validated",
                volume);
        return volume;
    }

    /** 登记一个新请求，先做一次匹配尝试。 */
    public VolumeRequest registerRequest(VolumeRequest request) {
        validateRequest(request);
        registry.registerRequest(request,
                RequestStatus.pending(RequestStatus.PendingReason.NO_VOLUMES,
                        "request registered, no binding attempted yet"));
        log.info("register-request input={} phase=PENDING", request);
        tryBind(request.getName());
        return request;
    }

    /**
     * 自动为请求挑选一个满足条件的卷并绑定。
     * 已绑定的请求直接返回当前状态（幂等）。
     *
     * @return 最新请求状态
     */
    public RequestStatus tryBind(String requestName) {
        VolumeRequest request = registry.findRequest(requestName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.REQUEST_NOT_FOUND,
                        "request not found: " + requestName));

        RequestStatus existing = registry.getRequestStatus(requestName).orElseThrow();
        if (existing.getPhase() == RequestPhase.BOUND) {
            log.info("try-bind request={} decision=skip basis=already-bound status={}",
                    requestName, existing);
            return existing;
        }

        var volumes = registry.listVolumes();
        if (volumes.isEmpty()) {
            return recordPending(request, RequestStatus.PendingReason.NO_VOLUMES,
                    "no volumes in pool");
        }

        // 分阶段过滤以确定"最贴近失败点"的原因。
        boolean anyClassMatched = false;
        boolean anyCapacityMatched = false;
        boolean anyModeMatched = false;
        boolean anyAvailable = false;

        for (PersistentVolume v : volumes) {
            if (!storageClassMatches(v, request)) {
                continue;
            }
            anyClassMatched = true;
            if (v.getCapacityBytes() < request.getCapacityBytes()) {
                continue;
            }
            anyCapacityMatched = true;
            if (!accessModesSatisfied(v, request)) {
                continue;
            }
            anyModeMatched = true;
            if (v.getState() != VolumeState.AVAILABLE) {
                continue;
            }
            anyAvailable = true;
            // 满足全部条件，尝试原子抢占
            if (attemptBind(request, v)) {
                return boundStatus(requestName, v);
            }
        }

        RequestStatus.PendingReason reason;
        String detail;
        if (!anyClassMatched) {
            reason = RequestStatus.PendingReason.STORAGE_CLASS_NOT_MATCHED;
            detail = "no volume with storageClass '" + norm(request.getStorageClass()) + "'";
        } else if (!anyCapacityMatched) {
            reason = RequestStatus.PendingReason.INSUFFICIENT_CAPACITY;
            detail = "no volume in class '" + norm(request.getStorageClass())
                    + "' has capacity >= " + request.getCapacityBytes();
        } else if (!anyModeMatched) {
            reason = RequestStatus.PendingReason.ACCESS_MODE_NOT_SUPPORTED;
            detail = "no volume of enough capacity supports modes " + request.getAccessModes();
        } else if (!anyAvailable) {
            reason = RequestStatus.PendingReason.ALL_MATCHING_VOLUMES_BOUND;
            detail = "all matching volumes are already BOUND";
        } else {
            // 理论上不可达：满足条件且可用却抢占失败时，其他线程已抢先绑定
            reason = RequestStatus.PendingReason.ALL_MATCHING_VOLUMES_BOUND;
            detail = "lost binding race to concurrent request";
        }
        return recordPending(request, reason, detail);
    }

    /**
     * 把请求显式绑定到指定卷。条件不满足时抛出携带稳定代码的异常；
     * 卷已被其他请求占用时抛 {@link ProvisionerErrorCode#VOLUME_ALREADY_BOUND}。
     */
    public RequestStatus bindToVolume(String requestName, String volumeName) {
        VolumeRequest request = registry.findRequest(requestName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.REQUEST_NOT_FOUND,
                        "request not found: " + requestName));
        PersistentVolume volume = registry.findVolume(volumeName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "volume not found: " + volumeName));

        // 已绑定到同一卷视为幂等成功
        RequestStatus existing = registry.getRequestStatus(requestName).orElseThrow();
        if (existing.getPhase() == RequestPhase.BOUND
                && volumeName.equals(existing.getBoundVolumeName())) {
            log.info("bind request={} volume={} decision=idempotent basis=already-same-binding",
                    requestName, volumeName);
            return existing;
        }

        if (!storageClassMatches(volume, request)) {
            throw mismatch(ProvisionerErrorCode.STORAGE_CLASS_NOT_MATCHED, request, volume,
                    "storageClass differs");
        }
        if (volume.getCapacityBytes() < request.getCapacityBytes()) {
            throw mismatch(ProvisionerErrorCode.CAPACITY_NOT_MATCHED, request, volume,
                    "volume capacity too small");
        }
        if (!accessModesSatisfied(volume, request)) {
            throw mismatch(ProvisionerErrorCode.ACCESS_MODE_NOT_MATCHED, request, volume,
                    "volume does not support requested modes");
        }

        boolean won = attemptBind(request, volume);
        if (!won) {
            throw new ProvisionerException(ProvisionerErrorCode.VOLUME_ALREADY_BOUND,
                    "volume '" + volumeName + "' is already bound to '"
                            + volume.getBoundRequestName() + "'");
        }
        return boundStatus(requestName, volume);
    }

    /**
     * 在卷锁内完成"检查 AVAILABLE -> 翻转 BOUND"，并同步更新请求状态。
     * 返回是否抢占成功。并发调用时只有一个线程返回 true。
     */
    private boolean attemptBind(VolumeRequest request, PersistentVolume volume) {
        Boolean won = registry.withVolumeLock(volume.getName(), v -> {
            if (v.getState() != VolumeState.AVAILABLE) {
                log.info("bind-race-lost request={} volume={} basis=volume-state={} boundTo={}",
                        request.getName(), v.getName(), v.getState(), v.getBoundRequestName());
                return Boolean.FALSE;
            }
            RequestPhase prior = registry.getRequestStatus(request.getName())
                    .map(RequestStatus::getPhase).orElse(null);
            v.setState(VolumeState.BOUND);
            v.setBoundRequestName(request.getName());
            v.setFailureReason(null);
            v.setLastOperation("bound:" + request.getName());
            registry.updateRequestStatus(request.getName(),
                    new RequestStatus(RequestPhase.BOUND, null, v.getName(),
                            "bound to volume " + v.getName()));
            log.info("bind-won request={} volume={} phase:{}->BOUND state:AVAILABLE->BOUND basis=capacity={}>={} modes⊆{} class={}",
                    request.getName(), v.getName(), prior, v.getCapacityBytes(),
                    request.getCapacityBytes(), v.getSupportedModes(), norm(v.getStorageClass()));
            return Boolean.TRUE;
        });
        return won;
    }

    private RequestStatus boundStatus(String requestName, PersistentVolume volume) {
        RequestStatus status = new RequestStatus(RequestPhase.BOUND, null,
                volume.getName(), "bound to volume " + volume.getName());
        registry.updateRequestStatus(requestName, status);
        return status;
    }

    private RequestStatus recordPending(VolumeRequest request,
                                        RequestStatus.PendingReason reason, String detail) {
        RequestStatus status = RequestStatus.pending(reason, detail);
        registry.updateRequestStatus(request.getName(), status);
        log.info("bind-pending request={} phase=PENDING reason={} detail='{}' input={}",
                request.getName(), reason, detail, request);
        return status;
    }

    // ---- 匹配判定 ----

    static boolean storageClassMatches(PersistentVolume v, VolumeRequest r) {
        return norm(v.getStorageClass()).equals(norm(r.getStorageClass()));
    }

    static boolean accessModesSatisfied(PersistentVolume v, VolumeRequest r) {
        Set<AccessMode> supported = v.getSupportedModes();
        for (AccessMode required : r.getAccessModes()) {
            if (!supported.contains(required)) {
                return false;
            }
        }
        return true;
    }

    static String norm(String storageClass) {
        return storageClass == null || storageClass.isBlank()
                ? DEFAULT_STORAGE_CLASS : storageClass.trim();
    }

    // ---- 入参校验 ----

    private static void validateRequest(VolumeRequest request) {
        if (request == null) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST, "request is null");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "request name must not be blank");
        }
        if (request.getCapacityBytes() <= 0) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "request capacityBytes must be positive, got: " + request.getCapacityBytes());
        }
        if (request.getAccessModes().isEmpty()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "request must declare at least one access mode");
        }
    }

    private static void validateVolume(PersistentVolume volume) {
        if (volume == null) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST, "volume is null");
        }
        if (volume.getName() == null || volume.getName().isBlank()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "volume name must not be blank");
        }
        if (volume.getCapacityBytes() <= 0) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "volume capacityBytes must be positive, got: " + volume.getCapacityBytes());
        }
        if (volume.getSupportedModes().isEmpty()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "volume must support at least one access mode");
        }
    }

    private static ProvisionerException mismatch(ProvisionerErrorCode code, VolumeRequest request,
                                                 PersistentVolume volume, String detail) {
        log.warn("bind-rejected request={} volume={} code={} basis={} reqCap={} volCap={} reqModes={} volModes={} reqClass='{}' volClass='{}'",
                request.getName(), volume.getName(), code, detail,
                request.getCapacityBytes(), volume.getCapacityBytes(),
                request.getAccessModes(), volume.getSupportedModes(),
                norm(request.getStorageClass()), norm(volume.getStorageClass()));
        return new ProvisionerException(code, detail
                + " [request=" + request.getName() + ", volume=" + volume.getName() + "]");
    }
}
