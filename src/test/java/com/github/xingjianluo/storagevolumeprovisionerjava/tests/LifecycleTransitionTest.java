package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestPhase;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期流转测试：Delete/Retain/Recycle 语义明确，
 * 重复释放、删除 BOUND 卷、复用非 RELEASED 卷等非法流转必须被拒绝（不静默忽略）。
 */
class LifecycleTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleTransitionTest.class);

    private VolumeRegistry registry;
    private LocalStorageBackend backend;
    private ProvisioningService provisioning;
    private LifecycleService lifecycle;
    private SnapshotService snapshots;

    @BeforeEach
    void setUp() {
        registry = new VolumeRegistry();
        backend = new LocalStorageBackend();
        provisioning = new ProvisioningService(registry, backend);
        lifecycle = new LifecycleService(registry, backend);
        snapshots = new SnapshotService(registry, backend);
    }

    private void bindOne(String volume, ReclaimPolicy policy, String claim) {
        provisioning.registerVolume(
                TestFixtures.volume(volume, TestFixtures.GB, policy));
        provisioning.registerRequest(
                TestFixtures.claim(claim, TestFixtures.GB, AccessMode.RWO));
    }

    @Test
    void retain_releaseKeepsVolumeReleasedWithData() {
        bindOne("rv", ReclaimPolicy.RETAIN, "rc");
        snapshots.writeVolumeData("rv", Map.of("k", "v"));

        VolumeState result = lifecycle.release("rc");
        log.info("case=retain input=BOUND+policy=RETAIN result={} dataPresent={}",
                result, !snapshots.readVolumeData("rv").isEmpty());
        assertEquals(VolumeState.RELEASED, result);
        PersistentVolume v = registry.findVolume("rv").orElseThrow();
        assertEquals(VolumeState.RELEASED, v.getState());
        assertNull(v.getBoundRequestName());
        assertEquals(Map.of("k", "v"), snapshots.readVolumeData("rv"),
                "Retain must keep data intact");
        assertEquals(RequestPhase.RELEASED,
                registry.getRequestStatus("rc").orElseThrow().getPhase());

        // 人工复用后回到 AVAILABLE，可再绑定
        lifecycle.reuseRetainedVolume("rv");
        assertEquals(VolumeState.AVAILABLE, registry.findVolume("rv").orElseThrow().getState());
        provisioning.registerRequest(TestFixtures.claim("rc2", TestFixtures.GB, AccessMode.RWO));
        RequestStatus s2 = registry.getRequestStatus("rc2").orElseThrow();
        assertEquals(RequestPhase.BOUND, s2.getPhase());
        log.info("case=retain reused->AVAILABLE reboundBy=rc2");
    }

    @Test
    void delete_releaseRemovesRecordAndData() {
        bindOne("dv", ReclaimPolicy.DELETE, "dc");
        snapshots.writeVolumeData("dv", Map.of("a", "b"));

        VolumeState result = lifecycle.release("dc");
        log.info("case=delete input=BOUND+policy=DELETE result={} recordExists={} dataExists={}",
                result, registry.findVolume("dv").isPresent(),
                backend.readData("dv").isEmpty());
        assertNull(result, "DELETE policy returns null (record removed)");
        assertTrue(registry.findVolume("dv").isEmpty(), "record removed from pool");
        assertTrue(backend.readData("dv").isEmpty(), "backend data removed");
        assertEquals(RequestPhase.RELEASED,
                registry.getRequestStatus("dc").orElseThrow().getPhase());
    }

    @Test
    void recycle_releaseWipesDataAndReturnsToAvailable() {
        bindOne("cv", ReclaimPolicy.RECYCLE, "cc");
        snapshots.writeVolumeData("cv", Map.of("secret", "data"));

        VolumeState result = lifecycle.release("cc");
        log.info("case=recycle input=BOUND+policy=RECYCLE result={} dataWiped={}",
                result, snapshots.readVolumeData("cv").isEmpty());
        assertEquals(VolumeState.AVAILABLE, result);
        assertTrue(snapshots.readVolumeData("cv").isEmpty(), "data must be wiped");
        assertNull(registry.findVolume("cv").orElseThrow().getBoundRequestName());

        // 回收后可被新请求再次绑定
        provisioning.registerRequest(TestFixtures.claim("cc2", TestFixtures.GB, AccessMode.RWO));
        assertEquals(RequestPhase.BOUND,
                registry.getRequestStatus("cc2").orElseThrow().getPhase());
        log.info("case=recycle reboundBy=cc2");
    }

    @Test
    void doubleRelease_isRejectedAsIllegalTransition() {
        bindOne("v", ReclaimPolicy.RETAIN, "c");
        lifecycle.release("c");
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> lifecycle.release("c"));
        assertEquals(ProvisionerErrorCode.ILLEGAL_TRANSITION, e.getCode());
        log.info("case=double-release code={} message='{}'", e.getCode(), e.getMessage());
    }

    @Test
    void deletingBoundVolume_isRejected() {
        bindOne("bv", ReclaimPolicy.RETAIN, "bc");
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> lifecycle.deleteVolume("bv"));
        assertEquals(ProvisionerErrorCode.ILLEGAL_TRANSITION, e.getCode());
        // 卷必须仍然存在且仍 BOUND，不能被静默删除
        assertEquals(VolumeState.BOUND, registry.findVolume("bv").orElseThrow().getState());
        log.info("case=delete-bound code={} volumeStillBOUND=true", e.getCode());
    }

    @Test
    void reusingAvailableVolume_isRejected() {
        provisioning.registerVolume(
                TestFixtures.volume("av", TestFixtures.GB, ReclaimPolicy.RETAIN));
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> lifecycle.reuseRetainedVolume("av"));
        assertEquals(ProvisionerErrorCode.ILLEGAL_TRANSITION, e.getCode());
        assertEquals(VolumeState.AVAILABLE,
                registry.findVolume("av").orElseThrow().getState());
        log.info("case=reuse-available code={} stateStill=AVAILABLE", e.getCode());
    }

    @Test
    void releasingPendingClaim_isRejected() {
        provisioning.registerVolume(
                TestFixtures.volume("small", TestFixtures.MB, ReclaimPolicy.RETAIN));
        provisioning.registerRequest(
                TestFixtures.claim("pending-c", TestFixtures.GB, AccessMode.RWO));
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> lifecycle.release("pending-c"));
        assertEquals(ProvisionerErrorCode.ILLEGAL_TRANSITION, e.getCode());
        assertFalse(registry.listRequests().isEmpty());
        log.info("case=release-pending code={} message='{}'", e.getCode(), e.getMessage());
    }

    @Test
    void deleteAvailableVolume_succeeds() {
        provisioning.registerVolume(
                TestFixtures.volume("free", TestFixtures.GB, ReclaimPolicy.RETAIN));
        lifecycle.deleteVolume("free");
        assertTrue(registry.findVolume("free").isEmpty());
        log.info("case=delete-available recordRemoved=true");
    }
}
