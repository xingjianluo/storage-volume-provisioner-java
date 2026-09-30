package com.github.xingjianluo.storagevolumeprovisionerjava.model;

/**
 * 卷访问模式（语义对齐 Kubernetes PV/PVC）。
 */
public enum AccessMode {
    /** ReadWriteOnce：仅允许单个节点以读写方式挂载。 */
    RWO("ReadWriteOnce"),
    /** ReadOnlyMany：允许多个节点以只读方式挂载。 */
    ROX("ReadOnlyMany"),
    /** ReadWriteMany：允许多个节点以读写方式挂载。 */
    RWX("ReadWriteMany");

    private final String kubernetesName;

    AccessMode(String kubernetesName) {
        this.kubernetesName = kubernetesName;
    }

    public String getKubernetesName() {
        return kubernetesName;
    }
}
