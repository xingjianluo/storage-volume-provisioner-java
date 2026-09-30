package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.DataRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ReclaimPolicyRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.ServiceHarness;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重点测试：卷回收与生命周期流转。
 * 覆盖 Delete/Retain/Recycle 语义及非法状态流转必须被拒绝（非静默忽略）。
 */
class LifecycleReclaimTest {

    private ServiceHarness bindPair(String policy) {
        ServiceHarness h = ServiceHarness.create();
        h.volumeService().provision(new VolumeRequest("v", 1024L, List.of("RWO"), "ssd", policy));
        // 写点数据，验证各策略对数据的处理
        h.volumeService().writeData("v", new DataRequest(0, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4})));
        h.bindingService().createClaim(new ClaimRequest("c", 1024L, List.of("RWO"), "ssd"));
        assertEquals("BOUND", h.bindingService().getClaim("c").state());
        return h;
    }

    @Test
    void deletePolicy_removesVolumeAndDevice() {
        ServiceHarness h = bindPair("DELETE");
        TestLog.input("回收策略", "DELETE");

        OperationResponse resp = h.reclaimService().deleteClaim("c");
        TestLog.state("删除绑定请求结果", resp.action() + " / " + resp.message());
        assertEquals("DELETED", resp.action());

        assertTrue(h.volumeStore().find("v").isEmpty(), "Delete 后卷应被移除");
        assertFalse(h.backend().deviceExists("v"), "Delete 后存储设备应删除");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.volumeService().get("v"));
        assertEquals(ReasonCode.VOLUME_NOT_FOUND, ex.getReason());
        TestLog.assert_("资源清除", "卷与设备均不存在，原因码 VOLUME_NOT_FOUND 可区分");
    }

    @Test
    void retainPolicy_keepsReleasedVolumeAndData() {
        ServiceHarness h = bindPair("RETAIN");
        TestLog.input("回收策略", "RETAIN");

        OperationResponse resp = h.reclaimService().deleteClaim("c");
        TestLog.state("删除绑定请求结果", resp.action() + " / " + resp.message());
        assertEquals("RETAINED", resp.action());

        var view = h.volumeService().get("v");
        assertEquals("RELEASED", view.state());
        assertTrue(h.backend().deviceExists("v"), "Retain 必须保留设备与数据");
        byte[] first4 = Base64.getDecoder().decode(h.volumeService().readData("v", 0, 4));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, first4);
        TestLog.assert_("数据保留", "RELEASED 卷数据仍为 [1,2,3,4]");

        // 人工放行后可重新参与绑定
        var reclaimed = h.reclaimService().releaseToAvailable("v");
        assertEquals("AVAILABLE", reclaimed.state());
        TestLog.state("人工放行", "RELEASED -> AVAILABLE");
    }

    @Test
    void recyclePolicy_wipesDataAndReturnsToAvailable() {
        ServiceHarness h = bindPair("RECYCLE");
        TestLog.input("回收策略", "RECYCLE");

        OperationResponse resp = h.reclaimService().deleteClaim("c");
        TestLog.state("删除绑定请求结果", resp.action() + " / " + resp.message());
        assertEquals("RECYCLED", resp.action());

        var view = h.volumeService().get("v");
        assertEquals("AVAILABLE", view.state());
        byte[] first4 = Base64.getDecoder().decode(h.volumeService().readData("v", 0, 4));
        assertArrayEquals(new byte[]{0, 0, 0, 0}, first4);
        TestLog.assert_("数据清零", "Recycle 后卷数据全 0，重回 AVAILABLE");
    }

    @Test
    void deletingBoundVolumeDirectly_isRejected_notSilentlyIgnored() {
        ServiceHarness h = bindPair("RETAIN");
        TestLog.input("非法操作", "直接删除仍被 claim 绑定的卷 v");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.reclaimService().deleteVolume("v"));
        TestLog.state("拒绝原因", ex.getReason());
        assertEquals(ReasonCode.VOLUME_IN_USE, ex.getReason());
        // 状态必须保持不变
        assertEquals("BOUND", h.volumeService().get("v").state());
        assertEquals("BOUND", h.bindingService().getClaim("c").state());
    }

    @Test
    void illegalTransition_availableToFailed_isRejected() {
        ServiceHarness h = ServiceHarness.create();
        h.volumeService().provision(new VolumeRequest("v2", 512L, List.of("RWO"), null, "DELETE"));
        TestLog.input("非法流转", "AVAILABLE -> FAILED（不在允许表中）");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.volumeStore().get("v2").markFailed());
        TestLog.state("拒绝原因", ex.getReason());
        assertEquals(ReasonCode.ILLEGAL_STATE_TRANSITION, ex.getReason());
        assertEquals("AVAILABLE", h.volumeService().get("v2").state());
    }

    @Test
    void changeReclaimPolicy_onlyAllowedWhenAvailable() {
        ServiceHarness h = bindPair("DELETE");
        TestLog.input("非法操作", "BOUND 卷修改回收策略");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.reclaimService().updateReclaimPolicy("v", new ReclaimPolicyRequest("RETAIN")));
        assertEquals(ReasonCode.RECLAIM_POLICY_CHANGE_REJECTED, ex.getReason());
        TestLog.state("拒绝原因", ex.getReason());

        // AVAILABLE 时允许修改
        h.reclaimService().deleteClaim("c"); // DELETE 会删卷，换一个 AVAILABLE 卷验证
        h.volumeService().provision(new VolumeRequest("v3", 512L, List.of("RWO"), null, "DELETE"));
        var updated = h.reclaimService().updateReclaimPolicy("v3", new ReclaimPolicyRequest("RETAIN"));
        assertEquals("RETAIN", updated.reclaimPolicy());
        TestLog.state("合法修改", "AVAILABLE 卷策略 -> RETAIN");
    }

    @Test
    void releasedVolumeCanBeDeletedByAdmin() {
        ServiceHarness h = bindPair("RETAIN");
        h.reclaimService().deleteClaim("c");
        assertEquals("RELEASED", h.volumeService().get("v").state());
        OperationResponse resp = h.reclaimService().deleteVolume("v");
        assertEquals("DELETED", resp.action());
        assertTrue(h.volumeStore().find("v").isEmpty());
        TestLog.assert_("RELEASED 卷可人工删除", resp.action());
    }
}
