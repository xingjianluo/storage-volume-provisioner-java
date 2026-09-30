package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 持久卷请求（PVC）状态。
 */
public enum ClaimState {
    /** 待处理：尚未找到满足条件的卷。 */
    PENDING,
    /** 已绑定：已独占绑定到某个卷。 */
    BOUND,
    /** 丢失：所绑定的卷已不存在。 */
    LOST
}
