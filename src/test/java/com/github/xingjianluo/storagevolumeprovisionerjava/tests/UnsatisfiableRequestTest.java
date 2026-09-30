package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimView;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.ServiceHarness;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重点测试：不可满足的请求。
 * 容量/访问模式/存储类不匹配的请求必须留在 PENDING，且原因码可区分。
 */
class UnsatisfiableRequestTest {

    private void provision(ServiceHarness h, String name, long cap, List<String> modes, String sc, String policy) {
        h.volumeService().provision(new VolumeRequest(name, cap, modes, sc, policy));
    }

    @Test
    void noAvailableVolume_reportsNoVolumeAvailable() {
        ServiceHarness h = ServiceHarness.create();
        TestLog.input("场景", "无任何卷，创建请求 c");
        ClaimView view = h.bindingService().createClaim(new ClaimRequest("c", 1024L, List.of("RWO"), null));
        TestLog.state("请求状态/原因", view.state() + " | " + view.pendingReason());
        assertEquals("PENDING", view.state());
        assertTrue(view.pendingReason().startsWith("NO_VOLUME_AVAILABLE"));
    }

    @Test
    void capacityMismatch_reportsCapacityReason_andStaysPending() {
        ServiceHarness h = ServiceHarness.create();
        provision(h, "small", 512L, List.of("RWO"), "ssd", "DELETE");
        TestLog.input("请求容量", 1024L);
        TestLog.basis("卷容量", "small=512，小于请求 1024");

        OperationResponse resp = h.bindingService().bind(stillPendingClaim(
                h, "c-cap", 1024L, List.of("RWO"), "ssd"));
        TestLog.state("绑定结果", resp.success() + " / " + resp.action());
        assertFalse(resp.success());
        ClaimView view = h.bindingService().getClaim("c-cap");
        assertEquals("PENDING", view.state());
        assertTrue(view.pendingReason().startsWith("CAPACITY_NOT_SATISFIED"), view.pendingReason());
        TestLog.assert_("原因可区分", view.pendingReason());
    }

    @Test
    void accessModeMismatch_reportsAccessModeReason() {
        ServiceHarness h = ServiceHarness.create();
        // 卷只支持 RWO；请求要求 RWX
        provision(h, "ro-vol", 4096L, List.of("RWO"), "ssd", "DELETE");
        TestLog.input("请求访问模式", List.of("RWX"));
        TestLog.basis("卷访问模式", "ro-vol=[RWO]，不包含 RWX");

        OperationResponse resp = h.bindingService().bind(stillPendingClaim(
                h, "c-mode", 1024L, List.of("RWX"), "ssd"));
        assertFalse(resp.success());
        ClaimView view = h.bindingService().getClaim("c-mode");
        assertEquals("PENDING", view.state());
        assertTrue(view.pendingReason().startsWith("ACCESS_MODE_NOT_SATISFIED"), view.pendingReason());
        TestLog.assert_("原因可区分", view.pendingReason());
    }

    @Test
    void storageClassMismatch_reportsStorageClassReason() {
        ServiceHarness h = ServiceHarness.create();
        provision(h, "hdd-vol", 4096L, List.of("RWO"), "hdd", "DELETE");
        TestLog.input("请求存储类", "ssd");
        TestLog.basis("卷存储类", "hdd-vol=hdd，与 ssd 不一致（优先级最高）");

        OperationResponse resp = h.bindingService().bind(stillPendingClaim(
                h, "c-sc", 1024L, List.of("RWO"), "ssd"));
        assertFalse(resp.success());
        ClaimView view = h.bindingService().getClaim("c-sc");
        assertEquals("PENDING", view.state());
        assertTrue(view.pendingReason().startsWith("STORAGE_CLASS_NOT_MATCHED"), view.pendingReason());
        TestLog.assert_("原因可区分", view.pendingReason());
    }

    @Test
    void priority_storageClassWinsOverCapacityAndAccessMode() {
        ServiceHarness h = ServiceHarness.create();
        // 同时存在三类不匹配：存储类优先级最高
        provision(h, "mismatch", 100L, List.of("RWO"), "hdd", "DELETE");
        TestLog.input("请求", "capacity=1024, modes=[RWX], sc=ssd（三项全不满足）");
        TestLog.basis("原因优先级", "存储类 > 容量 > 访问模式");

        h.bindingService().bind(stillPendingClaim(h, "c-all", 1024L, List.of("RWX"), "ssd"));
        ClaimView view = h.bindingService().getClaim("c-all");
        assertTrue(view.pendingReason().startsWith("STORAGE_CLASS_NOT_MATCHED"), view.pendingReason());
        TestLog.assert_("采用最高优先级原因", view.pendingReason());
    }

    @Test
    void boundVolumeLater_satisfiesPreviouslyPendingClaim() {
        ServiceHarness h = ServiceHarness.create();
        ClaimView claim = h.bindingService().createClaim(
                new ClaimRequest("c-later", 2048L, List.of("RWO"), "ssd"));
        assertEquals("PENDING", claim.state());
        TestLog.state("初始", "PENDING / " + claim.pendingReason());

        provision(h, "late-vol", 4096L, List.of("RWO"), "ssd", "DELETE");
        OperationResponse resp = h.bindingService().bind("c-later");
        TestLog.state("供给匹配卷后", resp.success() + " / " + resp.action());
        assertTrue(resp.success());
        assertEquals("late-vol", h.bindingService().getClaim("c-later").volumeRef());
    }

    @Test
    void exactMatch_bindsSuccessfully() {
        ServiceHarness h = ServiceHarness.create();
        provision(h, "good", 4096L, List.of("RWO", "RWX"), "ssd", "RETAIN");
        ClaimView view = h.bindingService().createClaim(
                new ClaimRequest("c-good", 4096L, List.of("RWX"), "ssd"));
        TestLog.state("完全匹配请求", view.state() + " / vol=" + view.volumeRef());
        assertEquals("BOUND", view.state());
        assertEquals("good", view.volumeRef());
    }

    /** 创建请求并断言它处于 PENDING（防止无意在创建时就被绑定）。 */
    private String stillPendingClaim(ServiceHarness h, String name, long cap, List<String> modes, String sc) {
        ClaimView view = h.bindingService().createClaim(new ClaimRequest(name, cap, modes, sc));
        assertEquals("PENDING", view.state(), "前置条件：请求应处于 PENDING");
        return name;
    }
}
