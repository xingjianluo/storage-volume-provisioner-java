package com.github.xingjianluo.storagevolumeprovisionerjava.error;

/**
 * 供给器对外暴露的稳定错误代码。
 * 调用方应按代码（而不是消息文本）区分失败原因。
 */
public enum ProvisionerErrorCode {

    // ---- 请求 / 绑定 ----
    /** 请求参数非法（缺失字段、容量非正等）。 */
    INVALID_REQUEST,
    /** 指定名称的请求不存在。 */
    REQUEST_NOT_FOUND,
    /** 指定名称的卷不存在。 */
    VOLUME_NOT_FOUND,
    /** 目标卷已被其他请求绑定。 */
    VOLUME_ALREADY_BOUND,
    /** 卷容量不足以满足请求。 */
    CAPACITY_NOT_MATCHED,
    /** 卷不支持请求要求的访问模式。 */
    ACCESS_MODE_NOT_MATCHED,
    /** 卷与请求的存储类不一致。 */
    STORAGE_CLASS_NOT_MATCHED,
    /** 当前状态下不允许执行该生命周期流转。 */
    ILLEGAL_TRANSITION,
    /** 未知的回收策略。 */
    UNKNOWN_RECLAIM_POLICY,

    // ---- 快照 ----
    /** 快照不存在。 */
    SNAPSHOT_NOT_FOUND,
    /** 创建/恢复快照过程中后端存储失败。 */
    STORAGE_BACKEND_FAILURE,

    // ---- 旧格式兼容 ----
    /** 旧记录缺少必填字段。 */
    LEGACY_MISSING_FIELD,
    /** 旧记录字段值非法（容量为负、未知枚举等）。 */
    LEGACY_INVALID_FIELD,
    /** 记录版本高于当前软件支持的最高版本，拒绝处理。 */
    UNSUPPORTED_RECORD_VERSION
}
