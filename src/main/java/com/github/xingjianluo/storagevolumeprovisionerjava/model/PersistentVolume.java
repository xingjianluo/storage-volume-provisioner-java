package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 持久化卷（PersistentVolume 语义）。
 * 状态可变，由服务层在锁保护下进行生命周期流转。
 */
public final class PersistentVolume {

    private final String name;
    private final long capacityBytes;
    private final Set<AccessMode> supportedModes;
    private final String storageClass;
    private final ReclaimPolicy reclaimPolicy;

    private VolumeState state;
    private String boundRequestName;
    private String failureReason;
    /** 记录当前状态由哪次操作写入，便于日志解释状态变化。 */
    private String lastOperation;

    public PersistentVolume(String name, long capacityBytes,
                            Set<AccessMode> supportedModes, String storageClass,
                            ReclaimPolicy reclaimPolicy) {
        this.name = Objects.requireNonNull(name, "name");
        this.capacityBytes = capacityBytes;
        this.supportedModes = supportedModes == null
                ? EnumSet.noneOf(AccessMode.class)
                : EnumSet.copyOf(supportedModes);
        this.storageClass = storageClass;
        this.reclaimPolicy = Objects.requireNonNull(reclaimPolicy, "reclaimPolicy");
        this.state = VolumeState.AVAILABLE;
    }

    public String getName() {
        return name;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public Set<AccessMode> getSupportedModes() {
        return EnumSet.copyOf(supportedModes);
    }

    public String getStorageClass() {
        return storageClass;
    }

    public ReclaimPolicy getReclaimPolicy() {
        return reclaimPolicy;
    }

    public VolumeState getState() {
        return state;
    }

    public void setState(VolumeState state) {
        this.state = state;
    }

    public String getBoundRequestName() {
        return boundRequestName;
    }

    public void setBoundRequestName(String boundRequestName) {
        this.boundRequestName = boundRequestName;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getLastOperation() {
        return lastOperation;
    }

    public void setLastOperation(String lastOperation) {
        this.lastOperation = lastOperation;
    }

    @Override
    public String toString() {
        return "PersistentVolume{name='" + name + "', capacityBytes=" + capacityBytes
                + ", supportedModes=" + supportedModes + ", storageClass='" + storageClass
                + "', reclaimPolicy=" + reclaimPolicy + ", state=" + state
                + ", boundRequestName='" + boundRequestName + "'}";
    }
}
