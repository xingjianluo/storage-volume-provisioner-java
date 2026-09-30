package com.github.xingjianluo.storagevolumeprovisionerjava.error;

/**
 * 可区分的失败原因码。
 *
 * <p>所有入口（HTTP、内部服务、测试）都使用同一套原因码，
 * 便于调用方按原因分支处理，也便于日志中解释判定依据。</p>
 */
public enum ReasonCode {

    // ---- 资源存在性 ----
    VOLUME_NOT_FOUND("未找到指定的卷"),
    CLAIM_NOT_FOUND("未找到指定的卷请求"),
    SNAPSHOT_NOT_FOUND("未找到指定的快照"),
    ALREADY_EXISTS("同名资源已存在"),

    // ---- 绑定匹配 ----
    NO_VOLUME_AVAILABLE("当前没有任何处于 AVAILABLE 状态的卷可以参与匹配"),
    CAPACITY_NOT_SATISFIED("没有卷的容量满足请求（容量不足）"),
    ACCESS_MODE_NOT_SATISFIED("没有卷同时支持请求要求的全部访问模式"),
    STORAGE_CLASS_NOT_MATCHED("没有卷的存储类与请求匹配"),
    VOLUME_ALREADY_BOUND("卷已经被其他请求绑定，不能重复绑定"),
    CLAIM_ALREADY_BOUND("请求已经绑定到卷，不能再次绑定"),

    // ---- 状态流转 ----
    ILLEGAL_STATE_TRANSITION("非法的生命周期状态流转，已被拒绝"),
    VOLUME_NOT_BOUND("卷未处于 BOUND 状态，无法执行该操作"),
    CLAIM_NOT_BOUND("请求未处于 BOUND 状态，无法执行该操作"),
    VOLUME_IN_USE("卷仍被已绑定的请求占用，不能直接回收/删除"),

    // ---- 回收 ----
    RECLAIM_FAILED("回收过程中存储层失败，卷转入 FAILED 等待人工处理"),
    RECLAIM_POLICY_CHANGE_REJECTED("只有 AVAILABLE 状态的卷才能修改回收策略"),

    // ---- 快照 ----
    SNAPSHOT_VOLUME_MISMATCH("快照不属于该卷"),
    SNAPSHOT_RESTORE_CAPACITY_MISMATCH("快照容量与卷当前容量不一致，不能恢复"),
    SNAPSHOT_RESTORE_FAILED("快照恢复过程中存储层失败，已整体回滚，卷保持恢复前状态"),

    // ---- 旧格式升级 ----
    UNSUPPORTED_RECORD_VERSION("记录格式版本无法识别（高于当前支持版本），拒绝处理"),
    LEGACY_FIELD_MISSING("旧格式记录缺少必填字段"),
    LEGACY_FIELD_INVALID("旧格式记录字段值非法"),

    // ---- 入参 ----
    INVALID_ARGUMENT("请求参数非法");

    private final String defaultMessage;

    ReasonCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
