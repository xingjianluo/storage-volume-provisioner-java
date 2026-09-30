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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发重复绑定测试：多线程同时争抢同一块卷时，
 * 必须恰好一个绑定成功，其余得到可区分的 VOLUME_ALREADY_BOUND 或停留在 PENDING，
 * 卷最终只绑定到唯一一个请求。
 */
class ConcurrentBindingTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentBindingTest.class);

    private ProvisioningService newServices(VolumeRegistry registry,
                                            LocalStorageBackend backend) {
        return new ProvisioningService(registry, backend);
    }

    @Test
    void oneVolume_manyClaims_exactlyOneWins_explicitBind() throws Exception {
        VolumeRegistry registry = new VolumeRegistry();
        LocalStorageBackend backend = new LocalStorageBackend();
        ProvisioningService provisioning = newServices(registry, backend);
        new LifecycleService(registry, backend);

        PersistentVolume vol = TestFixtures.volume("vol-1", TestFixtures.GB, ReclaimPolicy.RETAIN);
        provisioning.registerVolume(vol);

        int n = 32;
        for (int i = 0; i < n; i++) {
            provisioning.registerRequest(TestFixtures.claim("claim-" + i, TestFixtures.GB,
                    AccessMode.RWO));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger bound = new AtomicInteger();
        AtomicInteger alreadyBound = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final String claimName = "claim-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    RequestStatus s = provisioning.bindToVolume(claimName, "vol-1");
                    if (s.getPhase() == RequestPhase.BOUND) {
                        bound.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (ProvisionerException e) {
                    if (e.getCode() == ProvisionerErrorCode.VOLUME_ALREADY_BOUND) {
                        alreadyBound.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        log.info("concurrent-explicit-bind start threads={} input=one-vol(1Gi,RWO)+{}claims(1Gi,RWO)", n, n);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads must finish");

        log.info("concurrent-explicit-bind result bound={} alreadyBound={} other={}",
                bound.get(), alreadyBound.get(), other.get());
        assertEquals(1, bound.get(), "exactly one claim must bind the volume");
        assertEquals(n - 1, alreadyBound.get() + other.get());

        PersistentVolume after = registry.findVolume("vol-1").orElseThrow();
        assertEquals(VolumeState.BOUND, after.getState());
        assertNotNull(after.getBoundRequestName());
        long boundClaims = registry.listRequests().stream()
                .filter(r -> registry.getRequestStatus(r.getName())
                        .map(s -> s.getPhase() == RequestPhase.BOUND)
                        .orElse(false))
                .count();
        assertEquals(1, boundClaims, "only one request may be BOUND");
        log.info("concurrent-explicit-bind final volume={} winner={}",
                after.getName(), after.getBoundRequestName());
    }

    @Test
    void oneVolume_manyAutoBindRaces_exactlyOneWins() throws Exception {
        VolumeRegistry registry = new VolumeRegistry();
        LocalStorageBackend backend = new LocalStorageBackend();
        ProvisioningService provisioning = new ProvisioningService(registry, backend);
        new SnapshotService(registry, backend);

        provisioning.registerVolume(
                TestFixtures.volume("only-vol", 2 * TestFixtures.GB, ReclaimPolicy.DELETE));
        int n = 24;
        for (int i = 0; i < n; i++) {
            provisioning.registerRequest(TestFixtures.claim("auto-" + i, TestFixtures.GB,
                    AccessMode.RWO));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger bound = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final String claimName = "auto-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    RequestStatus s = provisioning.tryBind(claimName);
                    if (s.getPhase() == RequestPhase.BOUND) {
                        bound.incrementAndGet();
                    } else if (s.getPhase() == RequestPhase.PENDING) {
                        pending.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        log.info("concurrent-auto-bind start threads={} input=one-vol(2Gi)+{}claims(1Gi)", n, n);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("concurrent-auto-bind result bound={} pending={}", bound.get(), pending.get());
        assertEquals(1, bound.get(), "exactly one auto-bind succeeds");
        assertEquals(n - 1, pending.get());

        RequestStatus loserSample = registry.getRequestStatus(
                registry.listRequests().stream()
                        .filter(r -> registry.getRequestStatus(r.getName())
                                .map(s -> s.getPhase() == RequestPhase.PENDING).orElse(false))
                        .findFirst().orElseThrow().getName()).orElseThrow();
        assertEquals(RequestStatus.PendingReason.ALL_MATCHING_VOLUMES_BOUND,
                loserSample.getReason(), "losers must explain that matching volume was taken");
        log.info("concurrent-auto-bind loserReason={} detail='{}'",
                loserSample.getReason(), loserSample.getDetail());
    }
}
