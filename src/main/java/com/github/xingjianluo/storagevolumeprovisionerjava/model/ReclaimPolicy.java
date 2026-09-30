package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 卷回收策略（绑定的请求被删除后卷如何处理）。
 */
public enum ReclaimPolicy {
    /** 删除：清数据并删除卷。 */
    DELETE,
    /** 保留：保留卷与数据，卷停留在 RELEASED 等待人工处理。 */
    RETAIN,
    /** 回收：清空数据后卷重新变为 AVAILABLE。 */
    RECYCLE
}
