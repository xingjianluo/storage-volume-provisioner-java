package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import java.util.Objects;

/**
 * 卷在某一时刻的数据快照。
 * 数据内容由 {@code LocalStorageBackend} 持有；本对象只承载元数据。
 */
public final class Snapshot {

    private final String name;
    private final String sourceVolumeName;
    private final long createdAtEpochMillis;

    public Snapshot(String name, String sourceVolumeName, long createdAtEpochMillis) {
        this.name = Objects.requireNonNull(name, "name");
        this.sourceVolumeName = Objects.requireNonNull(sourceVolumeName, "sourceVolumeName");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getName() {
        return name;
    }

    public String getSourceVolumeName() {
        return sourceVolumeName;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    @Override
    public String toString() {
        return "Snapshot{name='" + name + "', sourceVolumeName='" + sourceVolumeName
                + "', createdAtEpochMillis=" + createdAtEpochMillis + "}";
    }
}
