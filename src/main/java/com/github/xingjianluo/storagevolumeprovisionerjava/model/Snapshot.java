package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import java.util.Objects;

/**
 * 卷快照：保存某一时刻卷的容量与数据内容。
 */
public class Snapshot {

    private final String name;
    private final String volumeName;
    private final long capacityBytes;
    private final byte[] data;
    private final long createdAtEpochMillis;

    public Snapshot(String name, String volumeName, long capacityBytes, byte[] data, long createdAtEpochMillis) {
        this.name = Objects.requireNonNull(name, "snapshot name");
        this.volumeName = Objects.requireNonNull(volumeName, "volumeName");
        this.capacityBytes = capacityBytes;
        this.data = Objects.requireNonNull(data, "snapshot data");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getName() {
        return name;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public byte[] getData() {
        return data;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }
}
