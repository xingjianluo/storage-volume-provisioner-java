package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 持久卷（PV）生命周期状态。
 */
public enum VolumeState {
    /** 可用：尚未被任何请求绑定，可参与匹配。 */
    AVAILABLE,
    /** 已绑定：已与某个 PVC 独占绑定。 */
    BOUND,
    /** 已释放：绑定的 PVC 已删除，但资源/数据尚未按回收策略处理完。 */
    RELEASED,
    /** 失败：回收等后台流程失败，需要人工介入。 */
    FAILED
}
