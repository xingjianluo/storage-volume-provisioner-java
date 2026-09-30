package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 持久化卷的生命周期状态。
 * 合法流转：
 * Available -> Bound -> Available（Recycle 回收后）
 * Available -> Bound -> Released（Delete/Retain 释放后）
 * Released -> Available（Retain 人工确认复用）
 * Released/Failed -> 终态（删除卷）
 * 任何绑定/恢复操作失败都会进入 Failed，而不会停留在中间状态。
 */
public enum VolumeState {
    /** 可用，等待绑定。 */
    AVAILABLE,
    /** 已绑定到某个请求。 */
    BOUND,
    /** 已从请求解绑，按回收策略等待处理。 */
    RELEASED,
    /** 操作（绑定/回收/快照恢复）失败，需要人工介入。 */
    FAILED
}
