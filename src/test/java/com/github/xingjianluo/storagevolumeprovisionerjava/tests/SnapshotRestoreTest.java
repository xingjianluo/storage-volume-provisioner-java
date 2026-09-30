package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.LifecycleService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ProvisioningService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.SnapshotService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeRegistry;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 快照恢复测试。
 * 正常路径：恢复后数据与快照一致；
 * 故障路径：后端在提交前失败时，卷数据必须保持恢复前内容、卷状态保持 BOUND，
 * 不允许出现"半新半旧"的混合数据。
 */
class SnapshotRestoreTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRestoreTest.class);

    private VolumeRegistry registry;
    private LocalStorageBackend backend;
    private ProvisioningService provisioning;
    private SnapshotService snapshots;
    private LifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        registry = new VolumeRegistry();
        backend = new LocalStorageBackend();
        provisioning = new ProvisioningService(registry, backend);
        snapshots = new SnapshotService(registry, backend);
        lifecycle = new LifecycleService(registry, backend);
    }

    private void setupBoundVolume() {
        provisioning.registerVolume(
                TestFixtures.volume("vol", TestFixtures.GB, ReclaimPolicy.RETAIN));
        provisioning.registerRequest(
                TestFixtures.claim("claim", TestFixtures.GB, AccessMode.RWO));
    }

    @Test
    void restore_replacesDataWithSnapshotContent() {
        setupBoundVolume();
        snapshots.writeVolumeData("vol", Map.of("v1", "1"));
        snapshots.createSnapshot("vol", "snap-v1");

        snapshots.writeVolumeData("vol", Map.of("v2", "2", "extra", "x"));
        log.info("case=restore input before={}", snapshots.readVolumeData("vol"));
        snapshots.restoreSnapshot("vol", "snap-v1");

        Map<String, String> after = snapshots.readVolumeData("vol");
        log.info("case=restore after-restore={}", after);
        assertEquals(Map.of("v1", "1"), after, "data must exactly match snapshot");
        assertEquals(VolumeState.BOUND, registry.findVolume("vol").orElseThrow().getState());
    }

    @Test
    void injectedFailureBeforeCommit_rollsBackCompletely() {
        setupBoundVolume();
        Map<String, String> original = Map.of("keep", "me", "n", "1");
        snapshots.writeVolumeData("vol", original);
        snapshots.createSnapshot("vol", "snap");

        // 卷继续写入新数据，然后用旧快照恢复；让后端在提交前失败
        snapshots.writeVolumeData("vol", Map.of("new", "data"));
        backend.setFailNextSnapshotOperation(true);
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> snapshots.restoreSnapshot("vol", "snap"));
        assertEquals(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE, e.getCode());

        Map<String, String> after = snapshots.readVolumeData("vol");
        log.info("case=rollback failureCode={} dataAfterFailedRestore={}", e.getCode(), after);
        assertEquals(Map.of("new", "data"), after,
                "failed restore must leave current data untouched (no half-and-half)");
        assertNotEquals(Map.of("keep", "me"), after,
                "sanity: current data differs from snapshot");
        assertEquals(VolumeState.BOUND, registry.findVolume("vol").orElseThrow().getState(),
                "volume state remains BOUND after rollback");

        // 故障只影响一次：复位后重试必须整体成功
        snapshots.restoreSnapshot("vol", "snap");
        assertEquals(original, snapshots.readVolumeData("vol"),
                "retry after failure must fully succeed");
        log.info("case=rollback retrySucceeded data={}", original);
    }

    @Test
    void snapshotCreateFailure_leavesNoSnapshotOrDataChange() {
        setupBoundVolume();
        snapshots.writeVolumeData("vol", Map.of("a", "1"));
        backend.setFailNextSnapshotOperation(true);
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> snapshots.createSnapshot("vol", "ghost"));
        assertEquals(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE, e.getCode());
        assertEquals(0, snapshots.listSnapshots("vol").size(),
                "failed snapshot creation leaves no snapshot record");
        assertEquals(Map.of("a", "1"), snapshots.readVolumeData("vol"));
        log.info("case=snapshot-create-rollback code={} noSnapshot=true dataIntact=true",
                e.getCode());
    }

    @Test
    void restoreOnReleasedVolume_isIllegalTransition_andDataUntouched() {
        setupBoundVolume();
        snapshots.writeVolumeData("vol", Map.of("k", "v"));
        snapshots.createSnapshot("vol", "s");
        lifecycle.release("claim"); // RETAIN -> RELEASED
        assertEquals(VolumeState.RELEASED, registry.findVolume("vol").orElseThrow().getState());

        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> snapshots.restoreSnapshot("vol", "s"));
        assertEquals(ProvisionerErrorCode.ILLEGAL_TRANSITION, e.getCode());
        assertEquals(Map.of("k", "v"), snapshots.readVolumeData("vol"));
        log.info("case=restore-released code={} dataUntouched=true state=RELEASED", e.getCode());
    }

    @Test
    void restoreMissingSnapshot_isNotFound() {
        setupBoundVolume();
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> snapshots.restoreSnapshot("vol", "nope"));
        assertEquals(ProvisionerErrorCode.SNAPSHOT_NOT_FOUND, e.getCode());
        log.info("case=restore-missing-snapshot code={}", e.getCode());
    }
}
