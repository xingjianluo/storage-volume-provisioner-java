package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestPhase;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 卷生命周期服务，负责释放（按回收策略执行 Delete/Retain/Recycle）、人工复用与删除。
 *
 * <h2>合法状态机</h2>
 * <pre>
 *   AVAILABLE ──bind──► BOUND
 *   BOUND ──release(DELETE)──► 后端数据被删，卷记录从池中移除（终态）
 *   BOUND ──release(RETAIN)──► RELEASED（数据保留，等待人工处理）
 *   BOUND ──release(RECYCLE)──► AVAILABLE（数据被清空，可再次供给）
 *   RELEASED ──reuse──► AVAILABLE
 *   AVAILABLE/RELEASED/FAILED ──delete──► 记录移除（BOUND 必须先释放）
 *   后端操作失败 ──► FAILED（卷不被静默放回到可绑定池）
 * </pre>
 *
 * <p>所有非法流转抛 {@link ProvisionerErrorCode#ILLEGAL_TRANSITION}，绝不静默忽略。
 * 释放时对应请求同步流转到 RELEASED。
 */
public class LifecycleService {

    /** DELETE 回收完成、卷已被移除时返回的哨兵状态（无对应枚举值）。 */
    private static final VolumeState DELETED;

    static {
        // 没有合适枚举，用 null 表达"记录已删除"；此处保留占位以明确语义
        DELETED = null;
    }

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final VolumeRegistry registry;
    private final LocalStorageBackend storageBackend;

    public LifecycleService(VolumeRegistry registry, LocalStorageBackend storageBackend) {
        this.registry = registry;
        this.storageBackend = storageBackend;
    }

    /**
     * 释放一个已绑定请求对应的卷，按卷自身的回收策略执行。
     *
     * @return 释放后的状态；DELETE 语义返回 null，表示卷记录已移除
     */
    public VolumeState release(String requestName) {
        VolumeRequest request = registry.findRequest(requestName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.REQUEST_NOT_FOUND,
                        "request not found: " + requestName));
        RequestStatus status = registry.getRequestStatus(requestName).orElseThrow();
        if (status.getPhase() != RequestPhase.BOUND || status.getBoundVolumeName() == null) {
            throw new ProvisionerException(ProvisionerErrorCode.ILLEGAL_TRANSITION,
                    "request '" + requestName + "' is not BOUND (phase=" + status.getPhase()
                            + "), cannot release");
        }
        String volumeName = status.getBoundVolumeName();

        // 锁内：校验 + 按策略执行后端动作 + 状态流转 + 请求置 RELEASED；返回是否需要移除卷记录
        Boolean deleteRecord = registry.withVolumeLock(volumeName, volume -> {
            if (volume.getState() != VolumeState.BOUND
                    || !requestName.equals(volume.getBoundRequestName())) {
                throw new ProvisionerException(ProvisionerErrorCode.ILLEGAL_TRANSITION,
                        "volume '" + volumeName + "' state=" + volume.getState()
                                + " boundTo='" + volume.getBoundRequestName()
                                + "', inconsistent with request '" + requestName + "'");
            }
            ReclaimPolicy policy = volume.getReclaimPolicy();
            log.info("release input request={} volume={} state=BOUND reclaimPolicy={}",
                    requestName, volumeName, policy);
            switch (policy) {
                case DELETE -> {
                    storageBackend.deleteData(volumeName);
                    volume.setBoundRequestName(null);
                    volume.setLastOperation("released:delete");
                    markRequestReleased(requestName, volumeName);
                    log.info("reclaim-delete volume={} basis=policy=DELETE data=removed", volumeName);
                    return Boolean.TRUE;
                }
                case RETAIN -> {
                    volume.setState(VolumeState.RELEASED);
                    volume.setBoundRequestName(null);
                    volume.setFailureReason(null);
                    volume.setLastOperation("released:retain");
                    markRequestReleased(requestName, volumeName);
                    log.info("reclaim-retain volume={} state:BOUND->RELEASED basis=policy=RETAIN data=kept",
                            volumeName);
                    return Boolean.FALSE;
                }
                case RECYCLE -> {
                    try {
                        storageBackend.writeData(volumeName, Map.of());
                    } catch (RuntimeException e) {
                        volume.setState(VolumeState.FAILED);
                        volume.setFailureReason("recycle wipe failed: " + e.getMessage());
                        log.error("reclaim-recycle-failed volume={} state:BOUND->FAILED",
                                volumeName, e);
                        throw new ProvisionerException(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE,
                                "recycle volume '" + volumeName + "' failed", e);
                    }
                    volume.setState(VolumeState.AVAILABLE);
                    volume.setBoundRequestName(null);
                    volume.setFailureReason(null);
                    volume.setLastOperation("released:recycle");
                    markRequestReleased(requestName, volumeName);
                    log.info("reclaim-recycle volume={} state:BOUND->AVAILABLE basis=policy=RECYCLE data=wiped",
                            volumeName);
                    return Boolean.FALSE;
                }
                default -> throw new ProvisionerException(
                        ProvisionerErrorCode.UNKNOWN_RECLAIM_POLICY,
                        "unknown reclaim policy: " + policy);
            }
        });

        // 锁外移除卷记录，避免在 withVolumeLock 回调内对注册表结构性修改
        if (Boolean.TRUE.equals(deleteRecord)) {
            registry.removeVolume(volumeName);
            log.info("release-done request={} volume={} result=REMOVED", requestName, volumeName);
            return DELETED;
        }
        VolumeState result = registry.findVolume(volumeName)
                .map(PersistentVolume::getState)
                .orElse(DELETED);
        log.info("release-done request={} volume={} result={}", requestName, volumeName, result);
        return result;
    }

    /** Retain 语义：人工确认把 RELEASED 卷重新置为 AVAILABLE。 */
    public PersistentVolume reuseRetainedVolume(String volumeName) {
        return registry.withVolumeLock(volumeName, volume -> {
            if (volume.getState() != VolumeState.RELEASED) {
                throw illegal(volume, "reuse",
                        "only RELEASED volumes can be reused, current=" + volume.getState());
            }
            VolumeState before = volume.getState();
            volume.setState(VolumeState.AVAILABLE);
            volume.setBoundRequestName(null);
            volume.setFailureReason(null);
            volume.setLastOperation("used:" + before);
            log.info("reuse volume={} state:{}->AVAILABLE basis=manual-retain-confirmation",
                    volumeName, before);
            return volume;
        });
    }

    /**
     * 删除卷：AVAILABLE / RELEASED / FAILED 可删；BOUND 必须先释放。
     * 后端数据与卷记录都会被移除。
     */
    public void deleteVolume(String volumeName) {
        registry.withVolumeLock(volumeName, volume -> {
            if (volume.getState() == VolumeState.BOUND) {
                throw illegal(volume, "delete",
                        "cannot delete a BOUND volume, release it first");
            }
            VolumeState before = volume.getState();
            storageBackend.deleteData(volumeName);
            volume.setBoundRequestName(null);
            volume.setLastOperation("deleted:" + before);
            log.info("delete volume={} state:{}->(removed) basis=explicit-delete",
                    volumeName, before);
            return null;
        });
        registry.removeVolume(volumeName);
    }

    private void markRequestReleased(String requestName, String volumeName) {
        registry.updateRequestStatus(requestName,
                new RequestStatus(RequestPhase.RELEASED, null, volumeName,
                        "released per reclaim policy of volume " + volumeName));
    }

    private static ProvisionerException illegal(PersistentVolume volume, String action, String detail) {
        log.warn("illegal-transition action={} volume={} state={} boundTo='{}' basis={}",
                action, volume.getName(), volume.getState(),
                volume.getBoundRequestName(), detail);
        return new ProvisionerException(ProvisionerErrorCode.ILLEGAL_TRANSITION,
                "illegal transition '" + action + "' on volume '" + volume.getName()
                        + "': " + detail);
    }
}
