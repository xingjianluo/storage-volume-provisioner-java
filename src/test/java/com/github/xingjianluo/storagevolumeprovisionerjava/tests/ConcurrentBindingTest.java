package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ClaimState;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolumeClaim;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.ServiceHarness;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.TestLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重点测试：并发重复绑定。
 * 同一块卷在任意并发下只能被绑定一次，其余请求必须留在 PENDING 或得到“已绑定”原因。
 */
class ConcurrentBindingTest {

    private ServiceHarness h;

    @BeforeEach
    void setUp() {
        h = ServiceHarness.create();
        // 一块 10MiB、支持 RWO/RWX 的卷（本地内存设备单盘上限约 2GiB）
        h.volumeService().provision(new com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest(
                "vol-1", 10L * 1024 * 1024, List.of("RWO", "RWX"), "ssd", "RETAIN"));
    }

    @Test
    void singleVolume_manyConcurrentClaims_onlyOneBinds() throws Exception {
        int n = 32;
        TestLog.input("并发请求数", n);
        TestLog.basis("唯一候选卷", "vol-1(AVAILABLE)，容量/模式/存储类均满足");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        ConcurrentHashMap<String, String> boundToVolume = new ConcurrentHashMap<>();
        AtomicInteger alreadyBoundErrors = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, n).<Callable<Void>>mapToObj(i -> () -> {
            String claim = "claim-" + i;
            // createClaim 创建即尝试绑定；随后再显式调用一次 bind，模拟重复触发
            h.bindingService().createClaim(new ClaimRequest(
                    claim, 1024L, List.of("RWO"), "ssd"));
            try {
                h.bindingService().bind(claim);
            } catch (ProvisioningException ex) {
                if (ex.getReason() == ReasonCode.CLAIM_ALREADY_BOUND) {
                    alreadyBoundErrors.incrementAndGet();
                } else {
                    throw ex;
                }
            }
            // 无论在“创建时”还是“显式 bind”时绑定，最终状态以注册表为准
            PersistentVolumeClaim finalState = h.claimStore().get(claim);
            if (finalState.getState() == ClaimState.BOUND) {
                boundToVolume.put(claim, finalState.getVolumeRef());
            } else {
                pending.incrementAndGet();
            }
            return null;
        }).toList();

        pool.invokeAll(tasks).forEach(f -> {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        PersistentVolume vol = h.volumeStore().get("vol-1");
        long boundClaims = h.claimStore().findAll().stream()
                .filter(c -> c.getState() == ClaimState.BOUND).count();

        TestLog.state("成功绑定的 claim 数", boundClaims);
        TestLog.state("卷最终状态/引用", vol.getState() + " / " + vol.getClaimRef());
        TestLog.state("仍 PENDING 数", pending.get());
        TestLog.state("CLAIM_ALREADY_BOUND 次数", alreadyBoundErrors.get());

        // 核心不变量：卷只被绑定一次
        assertEquals(1, boundClaims, "必须恰好一个 claim 绑定成功");
        assertEquals(VolumeState.BOUND, vol.getState());
        assertNotNull(vol.getClaimRef());

        // 每个成功绑定都必须指向同一块卷，且只有一个 claim 指向它
        assertEquals(1, boundToVolume.values().stream().distinct().count());
        long refsToVol = h.claimStore().findAll().stream()
                .filter(c -> "vol-1".equals(c.getVolumeRef())).count();
        assertEquals(1, refsToVol, "只能有一个 claim 引用 vol-1，杜绝重复绑定");

        TestLog.assert_("无重复绑定", "vol-1 引用数=" + refsToVol + "（期望1）");
    }

    @Test
    void repeatBindSameClaim_isRejectedWithDistinctReason() {
        // 独立环境：先创建请求（无候选卷，保持 PENDING），再供给卷并显式绑定
        ServiceHarness h2 = ServiceHarness.create();
        h2.bindingService().createClaim(new ClaimRequest("claim-only", 1024L, List.of("RWO"), "ssd"));
        h2.volumeService().provision(new com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest(
                "vol-2", 2048L, List.of("RWO"), "ssd", "DELETE"));

        var first = h2.bindingService().bind("claim-only");
        TestLog.state("第一次绑定", first.success() + " -> " + first.action());
        assertTrue(first.success());

        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h2.bindingService().bind("claim-only"));
        TestLog.state("重复绑定原因", ex.getReason());
        assertEquals(ReasonCode.CLAIM_ALREADY_BOUND, ex.getReason());

        PersistentVolumeClaim claim = h2.claimStore().get("claim-only");
        assertEquals(ClaimState.BOUND, claim.getState());
        assertEquals("vol-2", claim.getVolumeRef());
    }
}
