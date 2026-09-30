package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ReclaimPolicyRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeView;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ClaimState;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolumeClaim;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.ClaimStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.SnapshotStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 卷的回收与生命周期流转。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>删除 BOUND 请求：解除引用，PV 进入 RELEASED，并按回收策略处理；</li>
 *   <li>Delete=删设备+删卷（级联删快照）；Retain=保留卷与数据停在 RELEASED；
 *       Recycle=清空数据后重回 AVAILABLE；</li>
 *   <li>回收存储层失败 -> FAILED，可重试；任何非法状态流转都抛出带原因的异常。</li>
 * </ul>
 */
@Service
public class ReclaimService {

    private static final Logger log = LoggerFactory.getLogger(ReclaimService.class);

    private final VolumeStore volumeStore;
    private final ClaimStore claimStore;
    private final SnapshotStore snapshotStore;
    private final LocalStorageBackend storageBackend;

    public ReclaimService(VolumeStore volumeStore,
                          ClaimStore claimStore,
                          SnapshotStore snapshotStore,
                          LocalStorageBackend storageBackend) {
        this.volumeStore = volumeStore;
        this.claimStore = claimStore;
        this.snapshotStore = snapshotStore;
        this.storageBackend = storageBackend;
    }

    /** 删除一个卷请求（PVC）：若已绑定则触发对应卷的回收流程。 */
    public OperationResponse deleteClaim(String claimName) {
        PersistentVolumeClaim claim = claimStore.get(claimName);
        log.info("删除卷请求输入: claim={}, state={}, volumeRef={}",
                claimName, claim.getState(), claim.getVolumeRef());

        if (claim.getState() == ClaimState.LOST) {
            claimStore.remove(claimName);
            log.info("卷请求已删除: claim={} (此前 LOST)", claimName);
            return OperationResponse.ok("DELETED", "丢失的请求 " + claimName + " 已删除");
        }
        if (claim.getState() == ClaimState.PENDING) {
            claimStore.remove(claimName);
            log.info("待处理请求直接删除（无卷被占用）: claim={}", claimName);
            return OperationResponse.ok("DELETED",
                    "待处理请求 " + claimName + " 已删除，不影响任何卷");
        }

        // BOUND：先解除引用让卷进入 RELEASED，再按策略回收
        String volumeName = claim.getVolumeRef();
        PersistentVolume volume = volumeStore.get(volumeName);
        synchronized (volume) {
            volume.markReleased(claimName);
            claimStore.remove(claimName);
            log.info("解除绑定: claim={}, volume={} -> RELEASED, policy={}",
                    claimName, volumeName, volume.getReclaimPolicy());
            return applyReclaimPolicy(volume);
        }
    }

    /** 在卷已处于 RELEASED 的前提下执行回收策略。 */
    private OperationResponse applyReclaimPolicy(PersistentVolume volume) {
        ReclaimPolicy policy = volume.getReclaimPolicy();
        String name = volume.getName();
        try {
            switch (policy) {
                case DELETE -> {
                    int snapshots = snapshotStore.removeByVolume(name);
                    storageBackend.deleteDevice(name);
                    volumeStore.remove(name);
                    log.info("回收策略 DELETE 完成: volume={}, 级联删除快照={}个, 设备与卷已删除", name, snapshots);
                    return OperationResponse.ok("DELETED",
                            "卷 " + name + " 已按 Delete 策略删除（含数据与 " + snapshots + " 个快照）");
                }
                case RETAIN -> {
                    log.info("回收策略 RETAIN: volume={} 保留数据停留在 RELEASED，等待人工处理", name);
                    return OperationResponse.ok("RETAINED",
                            "卷 " + name + " 按 Retain 策略保留，状态为 RELEASED，数据未清除",
                            VolumeView.of(volume));
                }
                case RECYCLE -> {
                    long capacity = storageBackend.getCapacity(name);
                    storageBackend.write(name, 0, new byte[(int) capacity]);
                    volume.markAvailable();
                    log.info("回收策略 RECYCLE 完成: volume={} 数据已清零, -> AVAILABLE", name);
                    return OperationResponse.ok("RECYCLED",
                            "卷 " + name + " 已按 Recycle 策略清空数据并回到 AVAILABLE",
                            VolumeView.of(volume));
                }
                default -> throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                        "未知回收策略：" + policy);
            }
        } catch (RuntimeException ex) {
            volume.markFailed();
            log.warn("回收失败: volume={}, policy={}, 状态 -> FAILED, 原因={}", name, policy, ex.toString());
            if (ex instanceof ProvisioningException pe) {
                throw new ProvisioningException(ReasonCode.RECLAIM_FAILED,
                        name + " 回收失败(" + pe.getReason() + ")，卷已转入 FAILED");
            }
            throw new ProvisioningException(ReasonCode.RECLAIM_FAILED,
                    name + " 回收失败(" + ex.getMessage() + ")，卷已转入 FAILED");
        }
    }

    /** 管理面：删除一块卷。BOUND 卷拒绝；RELEASED(Retain) 与 FAILED 可人工删除。 */
    public OperationResponse deleteVolume(String volumeName) {
        PersistentVolume volume = volumeStore.get(volumeName);
        log.info("管理面删除卷输入: volume={}, state={}, claimRef={}",
                volumeName, volume.getState(), volume.getClaimRef());
        if (volume.getState() == VolumeState.BOUND) {
            throw new ProvisioningException(ReasonCode.VOLUME_IN_USE,
                    "卷 " + volumeName + " 仍被请求 " + volume.getClaimRef() + " 绑定，请先删除该请求");
        }
        int snapshots = snapshotStore.removeByVolume(volumeName);
        if (storageBackend.deviceExists(volumeName)) {
            storageBackend.deleteDevice(volumeName);
        }
        volumeStore.remove(volumeName);

        // 若恰好有 claim 记录引用了它（异常残留），标记 LOST
        for (PersistentVolumeClaim c : claimStore.findAll()) {
            if (volumeName.equals(c.getVolumeRef())) {
                c.markLost("绑定的卷 " + volumeName + " 已被管理面删除");
                log.warn("引用卷已删除，请求标记 LOST: claim={}", c.getName());
            }
        }
        log.info("管理面删除卷完成: volume={}, 级联删除快照={}个", volumeName, snapshots);
        return OperationResponse.ok("DELETED",
                "卷 " + volumeName + " 已删除（含数据与 " + snapshots + " 个快照）");
    }

    /** 管理面：把 RELEASED 的 Retain 卷重新置为 AVAILABLE（人工确认保留数据可用）。 */
    public VolumeView releaseToAvailable(String volumeName) {
        PersistentVolume volume = volumeStore.get(volumeName);
        if (volume.getState() != VolumeState.RELEASED) {
            throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                    "卷 " + volumeName + " 当前状态=" + volume.getState()
                            + "，只有 RELEASED 才能人工放行为 AVAILABLE");
        }
        volume.markAvailable();
        log.info("人工放行: volume={} RELEASED -> AVAILABLE（数据保留）", volumeName);
        return VolumeView.of(volume);
    }

    /** 修改卷回收策略（仅 AVAILABLE 可改）。 */
    public VolumeView updateReclaimPolicy(String volumeName, ReclaimPolicyRequest request) {
        if (request == null || request.reclaimPolicy() == null) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "reclaimPolicy 不能为空");
        }
        PersistentVolume volume = volumeStore.get(volumeName);
        ReclaimPolicy policy = RequestParsers.parseReclaimPolicy(request.reclaimPolicy(), null);
        volume.changeReclaimPolicy(policy);
        log.info("回收策略已修改: volume={}, newPolicy={}", volumeName, policy);
        return VolumeView.of(volume);
    }
}
