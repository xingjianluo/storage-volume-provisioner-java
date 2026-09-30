package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;

import java.util.List;

/** 卷的只读视图。 */
public record VolumeView(
        String name,
        long capacityBytes,
        List<String> accessModes,
        String storageClassName,
        String reclaimPolicy,
        String state,
        String claimRef,
        long version) {

    public static VolumeView of(PersistentVolume v) {
        return new VolumeView(
                v.getName(),
                v.getCapacityBytes(),
                v.getAccessModes().stream().map(Enum::name).sorted().toList(),
                v.getStorageClassName(),
                v.getReclaimPolicy().name(),
                v.getState().name(),
                v.getClaimRef(),
                v.getVersion());
    }
}
