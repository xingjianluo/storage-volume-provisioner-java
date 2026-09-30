package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 存储供给请求（PersistentVolumeClaim 语义）。
 * 描述工作负载对容量、访问模式与存储类的要求。
 * 不可变值对象；绑定结果（阶段、原因、目标卷）由服务层维护在注册表中。
 */
public final class VolumeRequest {

    private final String name;
    private final long capacityBytes;
    private final Set<AccessMode> accessModes;
    private final String storageClass;

    public VolumeRequest(String name, long capacityBytes,
                         Set<AccessMode> accessModes, String storageClass) {
        this.name = Objects.requireNonNull(name, "name");
        this.capacityBytes = capacityBytes;
        this.accessModes = accessModes == null
                ? EnumSet.noneOf(AccessMode.class)
                : EnumSet.copyOf(accessModes);
        this.storageClass = storageClass;
    }

    public String getName() {
        return name;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public Set<AccessMode> getAccessModes() {
        return EnumSet.copyOf(accessModes);
    }

    public String getStorageClass() {
        return storageClass;
    }

    @Override
    public String toString() {
        return "VolumeRequest{name='" + name + "', capacityBytes=" + capacityBytes
                + ", accessModes=" + accessModes + ", storageClass='" + storageClass + "'}";
    }
}
