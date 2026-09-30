package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

import java.util.List;

/** 创建一个卷请求（PVC）的请求体。 */
public record ClaimRequest(
        String name,
        Long capacityBytes,
        List<String> accessModes,
        String storageClassName) {
}
