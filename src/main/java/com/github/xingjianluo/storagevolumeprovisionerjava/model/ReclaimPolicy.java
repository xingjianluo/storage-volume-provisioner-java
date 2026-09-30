package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 卷释放后的回收策略。
 */
public enum ReclaimPolicy {
    /** 删除：释放后删除后端数据与卷记录。 */
    DELETE,
    /** 保留：释放后仅解绑，卷与数据保留为 Released，等待人工处理。 */
    RETAIN,
    /** 回收：清空数据后卷重新回到可用池供再次绑定。 */
    RECYCLE
}
