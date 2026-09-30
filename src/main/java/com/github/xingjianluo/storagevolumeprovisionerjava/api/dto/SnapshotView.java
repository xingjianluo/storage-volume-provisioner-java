package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

import com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot;

/** 快照的只读视图。 */
public record SnapshotView(
        String name,
        String volumeName,
        long capacityBytes,
        long createdAtEpochMillis,
        int dataLength) {

    public static SnapshotView of(Snapshot s) {
        return new SnapshotView(
                s.getName(),
                s.getVolumeName(),
                s.getCapacityBytes(),
                s.getCreatedAtEpochMillis(),
                s.getData().length);
    }
}
