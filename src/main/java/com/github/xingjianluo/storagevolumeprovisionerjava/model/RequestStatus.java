package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import java.util.Objects;

/**
 * 请求的当前绑定状态：阶段 + 可区分原因 + 目标卷。
 * 原因使用 {@link PendingReason} 的稳定代码，调用方可按代码分支处理。
 */
public final class RequestStatus {

    /**
     * 停留在 PENDING / 进入 UNSATISFIABLE 的可区分原因代码。
     */
    public enum PendingReason {
        /** 尚无任何卷存在。 */
        NO_VOLUMES,
        /** 存在卷，但没有容量足够的。 */
        INSUFFICIENT_CAPACITY,
        /** 容量足够的卷不支持请求要求的任一访问模式。 */
        ACCESS_MODE_NOT_SUPPORTED,
        /** 没有匹配存储类的卷。 */
        STORAGE_CLASS_NOT_MATCHED,
        /** 条件满足的卷当前都已被绑定。 */
        ALL_MATCHING_VOLUMES_BOUND,
        /** 请求参数本身非法（容量/访问模式缺失等）。 */
        INVALID_REQUEST
    }

    private final RequestPhase phase;
    private final PendingReason reason;
    private final String boundVolumeName;
    private final String detail;

    public RequestStatus(RequestPhase phase, PendingReason reason,
                         String boundVolumeName, String detail) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.reason = reason;
        this.boundVolumeName = boundVolumeName;
        this.detail = detail;
    }

    public static RequestStatus pending(PendingReason reason, String detail) {
        return new RequestStatus(RequestPhase.PENDING, reason, null, detail);
    }

    public RequestPhase getPhase() {
        return phase;
    }

    public PendingReason getReason() {
        return reason;
    }

    public String getBoundVolumeName() {
        return boundVolumeName;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "RequestStatus{phase=" + phase + ", reason=" + reason
                + ", boundVolumeName='" + boundVolumeName + "', detail='" + detail + "'}";
    }
}
