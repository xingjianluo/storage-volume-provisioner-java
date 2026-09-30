package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.RestoreRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.SnapshotView;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.SnapshotStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 快照创建与恢复。
 *
 * <p>恢复具备原子性：恢复前先完整备份当前数据，随后逐块写入新内容；
 * 写入期间任何失败都用备份整体回滚，卷数据保持恢复前内容，
 * 不会留下半新半旧的数据。回滚也失败时会以更高告警记录（双重故障）。</p>
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final VolumeStore volumeStore;
    private final SnapshotStore snapshotStore;
    private final LocalStorageBackend storageBackend;

    public SnapshotService(VolumeStore volumeStore, SnapshotStore snapshotStore, LocalStorageBackend storageBackend) {
        this.volumeStore = volumeStore;
        this.snapshotStore = snapshotStore;
        this.storageBackend = storageBackend;
    }

    public SnapshotView createSnapshot(String volumeName, String snapshotName) {
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "快照名称不能为空");
        }
        PersistentVolume volume = volumeStore.get(volumeName);
        // 已删除的卷无法快照；RELEASED/FAILED 数据仍在，允许快照（人工取证）
        if (volume.getState() == VolumeState.BOUND) {
            // 绑定中的卷做快照也允许，但记录调用上下文，便于解释
            log.info("对 BOUND 卷创建快照: volume={}, claimRef={}", volumeName, volume.getClaimRef());
        }
        byte[] data = storageBackend.snapshotRead(volumeName);
        Snapshot snapshot = new Snapshot(snapshotName, volumeName, data.length, data, System.currentTimeMillis());
        snapshotStore.put(snapshot);
        log.info("快照创建完成: snapshot={}, volume={}, capacityBytes={}, dataLen={}",
                snapshotName, volumeName, data.length, data.length);
        return SnapshotView.of(snapshot);
    }

    public OperationResponse restoreSnapshot(String snapshotName, RestoreRequest request) {
        if (request == null || request.volumeName() == null || request.volumeName().isBlank()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "volumeName 不能为空");
        }
        Snapshot snapshot = snapshotStore.get(snapshotName);
        PersistentVolume volume = volumeStore.get(request.volumeName());
        log.info("快照恢复输入: snapshot={}, snapshotVolume={}, targetVolume={}, targetState={}",
                snapshotName, snapshot.getVolumeName(), request.volumeName(), volume.getState());

        if (!snapshot.getVolumeName().equals(request.volumeName())) {
            throw new ProvisioningException(ReasonCode.SNAPSHOT_VOLUME_MISMATCH,
                    "快照属于卷 " + snapshot.getVolumeName() + "，不能恢复到卷 " + request.volumeName());
        }
        if (volume.getCapacityBytes() != snapshot.getCapacityBytes()) {
            throw new ProvisioningException(ReasonCode.SNAPSHOT_RESTORE_CAPACITY_MISMATCH,
                    "快照容量=" + snapshot.getCapacityBytes()
                            + " 与卷当前容量=" + volume.getCapacityBytes() + " 不一致");
        }

        byte[] backup = storageBackend.snapshotRead(request.volumeName());
        byte[] desired = snapshot.getData();
        try {
            log.info("开始逐块恢复: volume={}, bytes={}（中途失败将整体回滚）",
                    request.volumeName(), desired.length);
            storageBackend.restoreAll(request.volumeName(), desired);
            log.info("快照恢复整体成功: snapshot={} -> volume={}", snapshotName, request.volumeName());
            return OperationResponse.ok("RESTORED",
                    "卷 " + request.volumeName() + " 已整体恢复到快照 " + snapshotName,
                    SnapshotView.of(snapshot));
        } catch (RuntimeException ex) {
            log.warn("恢复中途失败，开始整体回滚: volume={}, failure={}", request.volumeName(), ex.toString());
            try {
                storageBackend.restoreAll(request.volumeName(), backup);
                log.warn("已整体回滚: volume={} 数据恢复到恢复前内容，无半新半旧数据", request.volumeName());
            } catch (RuntimeException rollbackEx) {
                log.error("回滚也失败（双重故障）: volume={}, rollbackFailure={}",
                        request.volumeName(), rollbackEx.toString());
                throw new ProvisioningException(ReasonCode.SNAPSHOT_RESTORE_FAILED,
                        "恢复失败且回滚失败，需要人工介入：" + rollbackEx.getMessage());
            }
            throw new ProvisioningException(ReasonCode.SNAPSHOT_RESTORE_FAILED,
                    request.volumeName() + " 恢复中途失败(" + ex.getMessage() + ")，已整体回滚");
        }
    }

    public Snapshot getSnapshot(String name) {
        return snapshotStore.get(name);
    }

    public List<SnapshotView> listSnapshots() {
        return snapshotStore.findAll().stream().map(SnapshotView::of).toList();
    }
}
