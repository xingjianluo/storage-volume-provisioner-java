package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeRequest;

import java.util.EnumSet;

/**
 * 测试数据工厂，统一输入构造方式，便于日志复现。
 */
final class TestFixtures {

    static final long GB = 1024L * 1024L * 1024L;
    static final long MB = 1024L * 1024L;

    private TestFixtures() {
    }

    static PersistentVolume volume(String name, long bytes, ReclaimPolicy policy) {
        return new PersistentVolume(name, bytes,
                EnumSet.of(AccessMode.RWO, AccessMode.RWX, AccessMode.ROX),
                "default", policy);
    }

    static PersistentVolume volume(String name, long bytes,
                                   EnumSet<AccessMode> modes,
                                   String storageClass, ReclaimPolicy policy) {
        return new PersistentVolume(name, bytes, modes, storageClass, policy);
    }

    static VolumeRequest claim(String name, long bytes, AccessMode... modes) {
        return new VolumeRequest(name, bytes, EnumSet.copyOf(
                java.util.List.of(modes)), "default");
    }

    static VolumeRequest claim(String name, long bytes,
                               EnumSet<AccessMode> modes, String storageClass) {
        return new VolumeRequest(name, bytes, modes, storageClass);
    }
}
