package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

import java.util.List;

/** 创建/供给一块卷的请求体。 */
public record VolumeRequest(
        String name,
        Long capacityBytes,
        List<String> accessModes,
        String storageClassName,
        String reclaimPolicy) {
}
