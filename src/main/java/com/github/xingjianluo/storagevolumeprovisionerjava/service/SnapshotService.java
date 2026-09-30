package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 快照服务：创建、列出、恢复快照。
 *
 * <h2>原子恢复契约</h2>
 * 恢复操作要么整体成功（数据与卷元数据同时落到新状态），
 * 要么整体回滚（卷数据保持恢复前内容，卷状态维持/回到 BOUND，不留半成品）：
 * <ol>
 *   <li>仅允许对 BOUND 卷恢复（避免游离卷数据被覆盖）。</li>
 *   <li>在卷锁 + 后端锁内，后端先在暂存区准备数据，提交失败抛异常，旧数据不变。</li>
 *   <li>准备成功后一次引用切换提交数据；只有提交成功才写 {@code lastOperation}。</li>
 *   <li>任何阶段异常：卷状态回到 BOUND、数据未动，并向上抛出可区分错误。</li>
 * </ol>
 */
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final VolumeRegistry registry;
    private final LocalStorageBackend storageBackend;

    /** snapshotName -> 快照元数据。 */
    private final ConcurrentMap<String, Snapshot> snapshotsByName = new ConcurrentHashMap<>();
    /** volumeName -> (snapshotName -> Snapshot)，按名字有序保证列出顺序稳定可复现。 */
    private final ConcurrentMap<String, NavigableMap<String, Snapshot>> byVolume = new ConcurrentHashMap<>();

    public SnapshotService(VolumeRegistry registry, LocalStorageBackend storageBackend) {
        this.registry = registry;
        this.storageBackend = storageBackend;
    }

    /** 为指定卷创建快照。只允许对存在的卷创建。 */
    public Snapshot createSnapshot(String volumeName, String snapshotName) {
        requireNonBlank(snapshotName, "snapshotName");
        PersistentVolume volume = registry.findVolume(volumeName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "volume not found: " + volumeName));

        // 先占位快照名，防止并发同名创建
        Snapshot meta = new Snapshot(snapshotName, volumeName, 0L);
        if (snapshotsByName.putIfAbsent(snapshotName, meta) != null) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "snapshot already exists: " + snapshotName);
        }
        boolean backendOk = false;
        try {
            // 后端在自身锁内完成数据深拷贝；创建失败时数据区无新增
            storageBackend.createSnapshotData(volumeName, snapshotName);
            backendOk = true;
        } finally {
            if (!backendOk) {
                snapshotsByName.remove(snapshotName);
            }
        }
        Snapshot committed = new Snapshot(snapshotName, volumeName, System.nanoTime());
        snapshotsByName.put(snapshotName, committed);
        byVolume.computeIfAbsent(volumeName, k -> new ConcurrentSkipListMap<>())
                .put(snapshotName, committed);
        log.info("snapshot-create snapshot={} volume={} state={} dataBytes={} basis=deep-copy",
                snapshotName, volumeName, volume.getState(),
                storageBackend.readData(volumeName).size());
        return committed;
    }

    /**
     * 用快照整体恢复卷数据。
     *
     * @throws ProvisionerException SNAPSHOT_NOT_FOUND / ILLEGAL_TRANSITION /
     *                              STORAGE_BACKEND_FAILURE，失败时卷数据与状态保持恢复前原样
     */
    public void restoreSnapshot(String volumeName, String snapshotName) {
        PersistentVolume volume = registry.findVolume(volumeName)
                .orElseThrow(() -> new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "volume not found: " + volumeName));
        Snapshot snapshot = snapshotsByName.get(snapshotName);
        if (snapshot == null || !volumeName.equals(snapshot.getSourceVolumeName())) {
            throw new ProvisionerException(ProvisionerErrorCode.SNAPSHOT_NOT_FOUND,
                    "snapshot '" + snapshotName + "' does not exist for volume '"
                            + volumeName + "'");
        }

        Map<String, String> beforeData = storageBackend.readData(volumeName);
        log.info("snapshot-restore-start volume={} snapshot={} state={} inputDataKeys={}",
                volumeName, snapshotName, volume.getState(), beforeData.keySet());

        registry.withVolumeLock(volumeName, v -> {
            if (v.getState() != VolumeState.BOUND) {
                throw new ProvisionerException(ProvisionerErrorCode.ILLEGAL_TRANSITION,
                        "snapshot restore only allowed on BOUND volume, current state="
                                + v.getState());
            }
            String priorOperation = v.getLastOperation();
            try {
                // 后端：暂存 -> 提交，失败则旧数据保持不变（后端内部保证）
                storageBackend.restoreSnapshotData(volumeName, snapshotName);
            } catch (ProvisionerException e) {
                // 数据未被修改，卷状态保持 BOUND，明确回滚结论
                v.setLastOperation(priorOperation);
                Map<String, String> after = storageBackend.readData(volumeName);
                log.error("snapshot-restore-rollback volume={} snapshot={} state=BOUND dataUnchanged={} code={} basis={}",
                        volumeName, snapshotName, after.equals(beforeData), e.getCode(),
                        e.getMessage());
                throw e;
            }
            v.setLastOperation("restored:" + snapshotName);
            Map<String, String> after = storageBackend.readData(volumeName);
            log.info("snapshot-restore-committed volume={} snapshot={} state=BOUND dataKeys={} basis=atomic-reference-swap",
                    volumeName, snapshotName, after.keySet());
            return null;
        });
    }

    /** 列出某卷的全部快照，按快照名字典序（结果稳定可复现）。 */
    public List<Snapshot> listSnapshots(ListQuery query) {
        String volumeName = query.volumeName();
        NavigableMap<String, Snapshot> map = byVolume.get(volumeName);
        if (map == null) {
            return List.of();
        }
        return new ArrayList<>(map.values());
    }

    /** 兼容保留：按卷名列出快照。 */
    public List<Snapshot> listSnapshots(String volumeName) {
        NavigableMap<String, Snapshot> map = byVolume.get(volumeName);
        if (map == null) {
            return List.of();
        }
        return new ArrayList<>(map.values());
    }

    /** 读取卷当前数据的不可变拷贝。 */
    public Map<String, String> readVolumeData(String volumeName) {
        return storageBackend.readData(volumeName);
    }

    /** 写入卷数据（测试/数据面入口，也可由控制器暴露）。 */
    public void writeVolumeData(String volumeName, Map<String, String> data) {
        if (registry.findVolume(volumeName).isEmpty()) {
            throw new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                    "volume not found: " + volumeName);
        }
        storageBackend.writeData(volumeName, data);
        log.info("volume-write volume={} keys={}", volumeName, data.keySet());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    field + " must not be blank");
        }
    }

    /** 列出快照的查询参数（预留过滤/分页扩展，目前只承载卷名）。 */
    public record ListQuery(String volumeName) {
    }
}
