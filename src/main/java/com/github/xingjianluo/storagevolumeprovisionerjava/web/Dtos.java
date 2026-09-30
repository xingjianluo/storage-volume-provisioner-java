package com.github.xingjianluo.storagevolumeprovisionerjava.web;

import java.util.List;

/**
 * REST 接口使用的数据传输对象。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 注册/供给一个新卷的请求体。 */
    public record RegisterVolumeRequest(
            String name,
            Long capacityBytes,
            List<String> accessModes,
            String storageClass,
            String reclaimPolicy) {
    }

    /** 创建存储请求（PVC）的请求体。 */
    public record RegisterClaimRequest(
            String name,
            Long capacityBytes,
            List<String> accessModes,
            String storageClass) {
    }

    /** 写卷数据的请求体。 */
    public record WriteDataRequest(java.util.Map<String, String> data) {
    }

    /** 创建快照请求体。 */
    public record CreateSnapshotRequest(String snapshotName) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String code, String message) {
    }
}
