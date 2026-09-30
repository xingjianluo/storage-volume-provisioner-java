package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 存储请求（PVC 语义）的处理阶段。
 */
public enum RequestPhase {
    /** 待处理：尚未找到满足条件的卷。 */
    PENDING,
    /** 已绑定到某个卷。 */
    BOUND,
    /** 请求被撤销/删除。 */
    RELEASED,
    /** 无法满足（例如容量不足、存储类不支持）。 */
    UNSATISFIABLE,
    /** 处理失败。 */
    FAILED
}
