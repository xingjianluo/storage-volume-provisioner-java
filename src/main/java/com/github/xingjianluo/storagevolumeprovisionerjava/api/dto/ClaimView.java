package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolumeClaim;

import java.util.List;

/** 卷请求的只读视图。 */
public record ClaimView(
        String name,
        long requestedCapacityBytes,
        List<String> accessModes,
        String storageClassName,
        String state,
        String volumeRef,
        String pendingReason) {

    public static ClaimView of(PersistentVolumeClaim c) {
        return new ClaimView(
                c.getName(),
                c.getRequestedCapacityBytes(),
                c.getAccessModes().stream().map(Enum::name).sorted().toList(),
                c.getStorageClassName(),
                c.getState().name(),
                c.getVolumeRef(),
                c.getPendingReason());
    }
}
