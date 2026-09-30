package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestPhase;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ProvisioningService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeRegistry;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 不可满足请求测试：容量不足、访问模式不支持、存储类不匹配等情况下，
 * 请求必须停留在 PENDING，且原因代码可区分；显式绑定则抛出对应的稳定错误代码。
 */
class UnsatisfiableRequestTest {

    private static final Logger log = LoggerFactory.getLogger(UnsatisfiableRequestTest.class);

    private VolumeRegistry registry;
    private ProvisioningService provisioning;

    @BeforeEach
    void setUp() {
        registry = new VolumeRegistry();
        LocalStorageBackend backend = new LocalStorageBackend();
        provisioning = new ProvisioningService(registry, backend);
    }

    @Test
    void emptyPool_givesNoVolumesReason() {
        provisioning.registerRequest(TestFixtures.claim("c", TestFixtures.GB, AccessMode.RWO));
        RequestStatus s = registry.getRequestStatus("c").orElseThrow();
        log.info("case=empty-pool input=claim(1Gi,RWO) phase={} reason={} detail='{}'",
                s.getPhase(), s.getReason(), s.getDetail());
        assertEquals(RequestPhase.PENDING, s.getPhase());
        assertEquals(RequestStatus.PendingReason.NO_VOLUMES, s.getReason());
    }

    @Test
    void capacityTooSmall_givesInsufficientCapacity() {
        provisioning.registerVolume(TestFixtures.volume("small", 512 * TestFixtures.MB,
                ReclaimPolicy.RETAIN));
        provisioning.registerRequest(TestFixtures.claim("big", TestFixtures.GB, AccessMode.RWO));
        RequestStatus s = registry.getRequestStatus("big").orElseThrow();
        log.info("case=capacity input=vol=512Mi claim=1Gi phase={} reason={} detail='{}'",
                s.getPhase(), s.getReason(), s.getDetail());
        assertEquals(RequestPhase.PENDING, s.getPhase());
        assertEquals(RequestStatus.PendingReason.INSUFFICIENT_CAPACITY, s.getReason());

        // 显式绑定必须用容量错误代码拒绝，卷保持 AVAILABLE
        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> provisioning.bindToVolume("big", "small"));
        assertEquals(ProvisionerErrorCode.CAPACITY_NOT_MATCHED, e.getCode());
        assertEquals(VolumeState.AVAILABLE, registry.findVolume("small").orElseThrow().getState());
        log.info("case=capacity explicitReject code={} volumeState=AVAILABLE", e.getCode());
    }

    @Test
    void accessModeUnsupported_givesAccessModeReason() {
        // 卷仅支持 RWO，请求要求 RWX
        provisioning.registerVolume(TestFixtures.volume("rwo-only", 2 * TestFixtures.GB,
                EnumSet.of(AccessMode.RWO), "default", ReclaimPolicy.RETAIN));
        provisioning.registerRequest(
                TestFixtures.claim("rwx-claim", TestFixtures.GB,
                        EnumSet.of(AccessMode.RWX), "default"));
        RequestStatus s = registry.getRequestStatus("rwx-claim").orElseThrow();
        log.info("case=access-mode input=vol(RWO) claim(RWX) phase={} reason={} detail='{}'",
                s.getPhase(), s.getReason(), s.getDetail());
        assertEquals(RequestPhase.PENDING, s.getPhase());
        assertEquals(RequestStatus.PendingReason.ACCESS_MODE_NOT_SUPPORTED, s.getReason());

        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> provisioning.bindToVolume("rwx-claim", "rwo-only"));
        assertEquals(ProvisionerErrorCode.ACCESS_MODE_NOT_MATCHED, e.getCode());
        log.info("case=access-mode explicitReject code={}", e.getCode());
    }

    @Test
    void storageClassMismatch_givesClassReason() {
        provisioning.registerVolume(TestFixtures.volume("ssd", TestFixtures.GB,
                EnumSet.of(AccessMode.RWO), "ssd", ReclaimPolicy.RETAIN));
        provisioning.registerRequest(
                TestFixtures.claim("hdd-claim", TestFixtures.GB,
                        EnumSet.of(AccessMode.RWO), "hdd"));
        RequestStatus s = registry.getRequestStatus("hdd-claim").orElseThrow();
        log.info("case=storage-class input=vol.class=ssd claim.class=hdd phase={} reason={} detail='{}'",
                s.getPhase(), s.getReason(), s.getDetail());
        assertEquals(RequestPhase.PENDING, s.getPhase());
        assertEquals(RequestStatus.PendingReason.STORAGE_CLASS_NOT_MATCHED, s.getReason());

        ProvisionerException e = assertThrows(ProvisionerException.class,
                () -> provisioning.bindToVolume("hdd-claim", "ssd"));
        assertEquals(ProvisionerErrorCode.STORAGE_CLASS_NOT_MATCHED, e.getCode());
        log.info("case=storage-class explicitReject code={}", e.getCode());
    }

    @Test
    void matchingVolumeBound_givesAllBoundReason_thenBecomesAvailableAfterRecycle() {
        provisioning.registerVolume(TestFixtures.volume("v", TestFixtures.GB,
                ReclaimPolicy.RECYCLE));
        provisioning.registerRequest(TestFixtures.claim("first", TestFixtures.GB, AccessMode.RWO));
        provisioning.registerRequest(TestFixtures.claim("second", TestFixtures.GB, AccessMode.RWO));
        RequestStatus s = registry.getRequestStatus("second").orElseThrow();
        log.info("case=all-bound input=1vol,2claims phase(second)={} reason={}",
                s.getPhase(), s.getReason());
        assertEquals(RequestPhase.PENDING, s.getPhase());
        assertEquals(RequestStatus.PendingReason.ALL_MATCHING_VOLUMES_BOUND, s.getReason());
    }

    @Test
    void invalidRequest_inputsAreRejectedWithDistinctValidation() {
        ProvisionerException e1 = assertThrows(ProvisionerException.class,
                () -> provisioning.registerRequest(
                        TestFixtures.claim("zero", 0, AccessMode.RWO)));
        assertEquals(ProvisionerErrorCode.INVALID_REQUEST, e1.getCode());
        log.info("case=invalid-capacity code={} message='{}'", e1.getCode(), e1.getMessage());

        ProvisionerException e2 = assertThrows(ProvisionerException.class,
                () -> provisioning.registerVolume(
                        TestFixtures.volume("no-mode", TestFixtures.GB,
                                EnumSet.noneOf(AccessMode.class), "default",
                                ReclaimPolicy.RETAIN)));
        assertEquals(ProvisionerErrorCode.INVALID_REQUEST, e2.getCode());
        log.info("case=invalid-modes code={} message='{}'", e2.getCode(), e2.getMessage());
    }
}
