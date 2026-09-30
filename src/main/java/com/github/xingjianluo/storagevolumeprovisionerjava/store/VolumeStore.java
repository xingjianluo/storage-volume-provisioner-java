package com.github.xingjianluo.storagevolumeprovisionerjava.store;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PV 注册表（内存实现，线程安全）。
 */
@Component
public class VolumeStore {

    private final ConcurrentHashMap<String, PersistentVolume> volumes = new ConcurrentHashMap<>();

    public void put(PersistentVolume volume) {
        if (volumes.putIfAbsent(volume.getName(), volume) != null) {
            throw new ProvisioningException(ReasonCode.ALREADY_EXISTS, "卷已存在：" + volume.getName());
        }
    }

    public Optional<PersistentVolume> find(String name) {
        return Optional.ofNullable(volumes.get(name));
    }

    public PersistentVolume get(String name) {
        return find(name).orElseThrow(() ->
                new ProvisioningException(ReasonCode.VOLUME_NOT_FOUND, "卷不存在：" + name));
    }

    /** 返回按名称排序的快照列表，保证遍历/匹配顺序确定（稳定可复现）。 */
    public List<PersistentVolume> findAll() {
        return volumes.values().stream()
                .sorted(java.util.Comparator.comparing(PersistentVolume::getName))
                .toList();
    }

    public void remove(String name) {
        if (volumes.remove(name) == null) {
            throw new ProvisioningException(ReasonCode.VOLUME_NOT_FOUND, "卷不存在：" + name);
        }
    }
}
