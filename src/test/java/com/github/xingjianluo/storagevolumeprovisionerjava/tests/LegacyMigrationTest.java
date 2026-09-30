package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.legacy.LegacyVolumeMigrator;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重点测试：旧格式卷记录的确定性升级与可区分的拒绝原因。
 */
class LegacyMigrationTest {

    private final LegacyVolumeMigrator migrator = new LegacyVolumeMigrator();
    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    void v1Record_upgradesDeterministically_andReproducibly() {
        String json = """
                { "formatVersion": 1, "id": "vol-old-1", "sizeGb": 10,
                  "mode": "RW", "reclaim": "delete", "diskClass": "ssd" }
                """;
        TestLog.input("旧记录 v1", json.replaceAll("\\s+", " "));

        PersistentVolume a = migrator.migrate(json);
        PersistentVolume b = migrator.migrate(json);

        TestLog.state("升级结果",
                "name=" + a.getName() + ", capacity=" + a.getCapacityBytes()
                        + ", modes=" + a.getAccessModes() + ", policy=" + a.getReclaimPolicy()
                        + ", sc=" + a.getStorageClassName() + ", state=" + a.getState());

        assertEquals("vol-old-1", a.getName());
        assertEquals(10L * GB, a.getCapacityBytes());
        assertEquals(EnumSetOf(AccessMode.RWO, AccessMode.RWX), a.getAccessModes());
        assertEquals(ReclaimPolicy.DELETE, a.getReclaimPolicy());
        assertEquals("ssd", a.getStorageClassName());
        assertEquals(VolumeState.AVAILABLE, a.getState());

        // 确定性：相同输入两次升级结果完全一致（容量、模式、策略、名称）
        assertEquals(a.getName(), b.getName());
        assertEquals(a.getCapacityBytes(), b.getCapacityBytes());
        assertEquals(a.getAccessModes(), b.getAccessModes());
        assertEquals(a.getReclaimPolicy(), b.getReclaimPolicy());
        assertEquals(a.getStorageClassName(), b.getStorageClassName());
        TestLog.assert_("确定性可复现", "两次升级字段逐一相等");
    }

    @Test
    void v1Defaults_modeIsRw_reclaimIsRetain_diskClassNull() {
        String json = """
                { "formatVersion": 1, "id": "vol-old-2", "sizeGb": 1 }
                """;
        TestLog.input("最小 v1 记录（缺省 mode/reclaim/diskClass）", json.trim());
        PersistentVolume v = migrator.migrate(json);
        assertEquals(EnumSetOf(AccessMode.RWO, AccessMode.RWX), v.getAccessModes());
        assertEquals(ReclaimPolicy.RETAIN, v.getReclaimPolicy(), "缺省回收策略必须是最保守的 RETAIN");
        assertNull(v.getStorageClassName());
        TestLog.state("缺省升级结果", "modes=" + v.getAccessModes() + ", policy=RETAIN, sc=null");
    }

    @Test
    void roMode_mapsToRwoAndRox() {
        PersistentVolume v = migrator.migrate(
                """
                { "formatVersion": 1, "id": "vol-ro", "sizeGb": 2, "mode": "RO" }
                """);
        assertEquals(EnumSetOf(AccessMode.RWO, AccessMode.ROX), v.getAccessModes());
        TestLog.state("RO 映射", v.getAccessModes());
    }

    @Test
    void higherVersion_isRejectedExplicitly_notTreatedAsLegacy() {
        for (String json : List.of(
                """
                { "formatVersion": 2, "id": "x", "sizeGb": 1 }
                """,
                """
                { "formatVersion": 99, "id": "x", "sizeGb": 1 }
                """)) {
            TestLog.input("高版本记录", json.trim());
            ProvisioningException ex = assertThrows(ProvisioningException.class, () -> migrator.migrate(json));
            TestLog.state("拒绝原因", ex.getReason());
            assertEquals(ReasonCode.UNSUPPORTED_RECORD_VERSION, ex.getReason());
        }
    }

    @Test
    void missingVersion_isRejected() {
        String json = """
                { "id": "x", "sizeGb": 1 }
                """;
        TestLog.input("无版本记录", json.trim());
        ProvisioningException ex = assertThrows(ProvisioningException.class, () -> migrator.migrate(json));
        assertEquals(ReasonCode.UNSUPPORTED_RECORD_VERSION, ex.getReason());
        TestLog.state("拒绝原因", ex.getReason() + "（缺版本不可按旧记录处理）");
    }

    @Test
    void missingRequiredFields_reportMissingReason() {
        assertMissing("{ \"formatVersion\": 1, \"sizeGb\": 1 }", "id");
        assertMissing("{ \"formatVersion\": 1, \"id\": \"x\" }", "sizeGb");
    }

    @Test
    void invalidFieldValues_reportInvalidReason() {
        assertInvalid("{ \"formatVersion\": 1, \"id\": \"x\", \"sizeGb\": 0 }");
        assertInvalid("{ \"formatVersion\": 1, \"id\": \"x\", \"sizeGb\": -5 }");
        assertInvalid("{ \"formatVersion\": 1, \"id\": \"x\", \"sizeGb\": \"abc\" }");
        assertInvalid("{ \"formatVersion\": 1, \"id\": \"x\", \"sizeGb\": 1, \"mode\": \"XW\" }");
        assertInvalid("{ \"formatVersion\": 1, \"id\": \"x\", \"sizeGb\": 1, \"reclaim\": \"shred\" }");
        assertInvalid("{ \"formatVersion\": \"x\", \"id\": \"x\", \"sizeGb\": 1 }");
        assertInvalid("not-a-json");
    }

    private void assertMissing(String json, String field) {
        TestLog.input("缺字段记录(" + field + ")", json);
        ProvisioningException ex = assertThrows(ProvisioningException.class, () -> migrator.migrate(json));
        TestLog.state("拒绝原因", ex.getReason());
        assertEquals(ReasonCode.LEGACY_FIELD_MISSING, ex.getReason());
    }

    private void assertInvalid(String json) {
        TestLog.input("非法字段记录", json);
        ProvisioningException ex = assertThrows(ProvisioningException.class, () -> migrator.migrate(json));
        TestLog.state("拒绝原因", ex.getReason());
        assertEquals(ReasonCode.LEGACY_FIELD_INVALID, ex.getReason());
    }

    private static java.util.EnumSet<AccessMode> EnumSetOf(AccessMode... modes) {
        return java.util.EnumSet.copyOf(java.util.List.of(modes));
    }
}
