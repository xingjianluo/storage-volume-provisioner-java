package com.github.xingjianluo.storagevolumeprovisionerjava.store;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快照注册表（内存实现，线程安全）。
 */
@Component
public class SnapshotStore {

    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public void put(Snapshot snapshot) {
        if (snapshots.putIfAbsent(snapshot.getName(), snapshot) != null) {
            throw new ProvisioningException(ReasonCode.ALREADY_EXISTS, "快照已存在：" + snapshot.getName());
        }
    }

    public Optional<Snapshot> find(String name) {
        return Optional.ofNullable(snapshots.get(name));
    }

    public Snapshot get(String name) {
        return find(name).orElseThrow(() ->
                new ProvisioningException(ReasonCode.SNAPSHOT_NOT_FOUND, "快照不存在：" + name));
    }

    public List<Snapshot> findByVolume(String volumeName) {
        return snapshots.values().stream()
                .filter(s -> s.getVolumeName().equals(volumeName))
                .sorted(java.util.Comparator.comparing(Snapshot::getName))
                .toList();
    }

    public List<Snapshot> findAll() {
        return snapshots.values().stream()
                .sorted(java.util.Comparator.comparing(Snapshot::getName))
                .toList();
    }

    /** 删除某块卷的全部快照，返回被删除的数量（删除卷时级联清理）。 */
    public int removeByVolume(String volumeName) {
        int[] removed = {0};
        snapshots.entrySet().removeIf(e -> {
            if (e.getValue().getVolumeName().equals(volumeName)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}
