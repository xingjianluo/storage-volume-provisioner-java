package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 卷的访问模式。请求方声明其工作负载需要的访问方式，
 * 绑定时请求的访问模式必须与卷支持的访问模式兼容。
 */
public enum AccessMode {
    /** 只读多节点：可被多节点以只读方式挂载。 */
    ROX,
    /** 读写单节点：同一时刻只能被一个节点以读写方式挂载。 */
    RWO,
    /** 读写多节点：可被多节点同时以读写方式挂载。 */
    RWX
}
