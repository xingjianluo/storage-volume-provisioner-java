package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;

import java.util.EnumSet;
import java.util.Objects;

/**
 * 持久卷请求（PersistentVolumeClaim）。
 */
public class PersistentVolumeClaim {

    private final String name;
    private final long requestedCapacityBytes;
    private final EnumSet<AccessMode> accessModes;
    private final String storageClassName;
    private volatile ClaimState state = ClaimState.PENDING;
    private volatile String volumeRef;
    /** 最近一次未能完成绑定的原因码，可解释“为什么还在 PENDING”。 */
    private volatile String pendingReason;

    public PersistentVolumeClaim(String name,
                                 long requestedCapacityBytes,
                                 EnumSet<AccessMode> accessModes,
                                 String storageClassName) {
        this.name = Objects.requireNonNull(name, "claim name");
        this.requestedCapacityBytes = requestedCapacityBytes;
        this.accessModes = Objects.requireNonNull(accessModes, "accessModes");
        this.storageClassName = storageClassName;
    }

    public String getName() {
        return name;
    }

    public long getRequestedCapacityBytes() {
        return requestedCapacityBytes;
    }

    public EnumSet<AccessMode> getAccessModes() {
        return accessModes;
    }

    public String getStorageClassName() {
        return storageClassName;
    }

    public ClaimState getState() {
        return state;
    }

    public String getVolumeRef() {
        return volumeRef;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    /** 原子绑定：仅 PENDING 可绑定，重复绑定直接拒绝。 */
    public synchronized boolean tryBind(String volumeName) {
        if (state != ClaimState.PENDING) {
            return false;
        }
        this.state = ClaimState.BOUND;
        this.volumeRef = Objects.requireNonNull(volumeName, "volumeName");
        this.pendingReason = null;
        return true;
    }

    /** 记录一次未满足的匹配原因，保持 PENDING。 */
    public synchronized void markPending(ReasonCode reason, String detail) {
        this.state = ClaimState.PENDING;
        this.pendingReason = reason.name() + "：" + detail;
    }

    /** BOUND -> LOST：所绑定的卷已不存在（删除策略生效等场景）。 */
    public synchronized void markLost(String detail) {
        if (state != ClaimState.BOUND) {
            throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                    "请求 " + name + " 当前状态=" + state + "，只有 BOUND 才能变为 LOST");
        }
        this.state = ClaimState.LOST;
        this.volumeRef = null;
        this.pendingReason = detail;
    }

    /** 解除绑定（用于并发回滚）。 */
    public synchronized void rollbackBind() {
        if (state == ClaimState.BOUND) {
            this.state = ClaimState.PENDING;
            this.volumeRef = null;
        }
    }
}
