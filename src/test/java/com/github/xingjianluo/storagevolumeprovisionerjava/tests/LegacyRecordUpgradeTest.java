package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.compat.LegacyRecordUpgrader;
import com.github.xingjianluo.storagevolumeprovisionerjava.compat.LegacyVolumeRecord;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 旧格式升级测试。
 * 重点：v1/v2 确定性升级且结果可复现；缺失字段与非法字段给出不同错误代码；
 * 更高版本（v3+）必须明确拒绝，而不是按旧记录猜测处理。
 */
class LegacyRecordUpgradeTest {

    private static final Logger log = LoggerFactory.getLogger(LegacyRecordUpgradeTest.class);

    private final LegacyRecordUpgrader upgrader = new LegacyRecordUpgrader();

    private LegacyVolumeRecord record(int version, String... kv) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }
        return new LegacyVolumeRecord(version, fields);
    }

    @Test
    void v1_upgradesDeterministically_withDefaults() {
        PersistentVolume a = upgrader.upgrade(record(1,
                "name", "old-disk", "size", "10Gi", "mode", "RW"));
        PersistentVolume b = upgrader.upgrade(record(1,
                "name", "old-disk", "size", "10Gi", "mode", "RW"));

        log.info("case=v1-defaults input=10Gi/RW name=old-disk -> bytes={} modes={} class={} policy={} state={} op={}",
                a.getCapacityBytes(), a.getSupportedModes(), a.getStorageClass(),
                a.getReclaimPolicy(), a.getState(), a.getLastOperation());

        assertEquals(10L * 1024 * 1024 * 1024, a.getCapacityBytes());
        assertEquals(EnumSetOf(AccessMode.RWO), a.getSupportedModes());
        assertEquals("default", a.getStorageClass(), "v1 missing class defaults to default");
        assertEquals(ReclaimPolicy.RETAIN, a.getReclaimPolicy(), "v1 missing reclaim defaults to Retain");
        assertEquals(VolumeState.AVAILABLE, a.getState());

        // 确定性：重复升级结果完全一致
        assertEquals(a.getName(), b.getName());
        assertEquals(a.getCapacityBytes(), b.getCapacityBytes());
        assertEquals(a.getSupportedModes(), b.getSupportedModes());
        assertEquals(a.getStorageClass(), b.getStorageClass());
        assertEquals(a.getReclaimPolicy(), b.getReclaimPolicy());
        log.info("case=v1-deterministic repeatedUpgradeEqual=true");
    }

    private static java.util.Set<AccessMode> EnumSetOf(AccessMode... modes) {
        return java.util.EnumSet.copyOf(java.util.List.of(modes));
    }

    @Test
    void v1_sizesParseInBinaryUnits() {
        assertEquals(1024, upgrader.upgrade(record(1, "name", "k", "size", "1Ki", "mode", "RO"))
                .getCapacityBytes());
        assertEquals(1024L * 1024 * 1024,
                upgrader.upgrade(record(1, "name", "g", "size", "1Gi", "mode", "RO"))
                        .getCapacityBytes());
        assertEquals(2048L,
                upgrader.upgrade(record(1, "name", "b", "size", "2048", "mode", "RO"))
                        .getCapacityBytes());
        log.info("case=v1-units 1Ki=1024 1Gi=1073741824 2048(plain bytes)=2048");
    }

    @Test
    void v2_upgradesMultiModes() {
        PersistentVolume v = upgrader.upgrade(record(2,
                "name", "new-disk",
                "sizeMb", "512",
                "modes", "ReadWriteMany,ReadOnlyMany",
                "storageClass", "fast",
                "reclaim", "Delete"));
        log.info("case=v2 input=512Mi/RWX+ROX/fast/Delete -> bytes={} modes={} class={} policy={}",
                v.getCapacityBytes(), v.getSupportedModes(), v.getStorageClass(),
                v.getReclaimPolicy());
        assertEquals(512L * 1024 * 1024, v.getCapacityBytes());
        assertEquals(EnumSetOf(AccessMode.RWX, AccessMode.ROX), v.getSupportedModes());
        assertEquals("fast", v.getStorageClass());
        assertEquals(ReclaimPolicy.DELETE, v.getReclaimPolicy());
    }

    @Test
    void missingField_isDistinctFromInvalidField_v1() {
        ProvisionerException missing = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(1, "name", "x", "mode", "RW")));
        assertEquals(ProvisionerErrorCode.LEGACY_MISSING_FIELD, missing.getCode());
        log.info("case=v1-missing code={} message='{}'", missing.getCode(), missing.getMessage());

        ProvisionerException invalidSize = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(1, "name", "x", "size", "big", "mode", "RW")));
        assertEquals(ProvisionerErrorCode.LEGACY_INVALID_FIELD, invalidSize.getCode());
        log.info("case=v1-invalid-size code={} message='{}'",
                invalidSize.getCode(), invalidSize.getMessage());

        ProvisionerException invalidMode = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(
                        record(1, "name", "x", "size", "1Gi", "mode", "WEIRD")));
        assertEquals(ProvisionerErrorCode.LEGACY_INVALID_FIELD, invalidMode.getCode());
        log.info("case=v1-invalid-mode code={} message='{}'",
                invalidMode.getCode(), invalidMode.getMessage());
    }

    @Test
    void v2_missingAndInvalidFields_areDistinguishable() {
        ProvisionerException missingClass = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(2,
                        "name", "x", "sizeMb", "10",
                        "modes", "ReadWriteOnce", "reclaim", "Retain")));
        assertEquals(ProvisionerErrorCode.LEGACY_MISSING_FIELD, missingClass.getCode());
        log.info("case=v2-missing-class code={} message='{}'",
                missingClass.getCode(), missingClass.getMessage());

        ProvisionerException negativeSize = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(2,
                        "name", "x", "sizeMb", "-3",
                        "modes", "ReadWriteOnce", "storageClass", "c", "reclaim", "Retain")));
        assertEquals(ProvisionerErrorCode.LEGACY_INVALID_FIELD, negativeSize.getCode());
        log.info("case=v2-negative-size code={} message='{}'",
                negativeSize.getCode(), negativeSize.getMessage());

        ProvisionerException badReclaim = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(2,
                        "name", "x", "sizeMb", "3",
                        "modes", "ReadWriteOnce", "storageClass", "c",
                        "reclaim", "Nuke")));
        assertEquals(ProvisionerErrorCode.LEGACY_INVALID_FIELD, badReclaim.getCode());
        log.info("case=v2-bad-reclaim code={} message='{}'",
                badReclaim.getCode(), badReclaim.getMessage());

        ProvisionerException emptyModes = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(2,
                        "name", "x", "sizeMb", "3",
                        "modes", "", "storageClass", "c", "reclaim", "Retain")));
        assertEquals(ProvisionerErrorCode.LEGACY_INVALID_FIELD, emptyModes.getCode());
        log.info("case=v2-empty-modes code={} message='{}'",
                emptyModes.getCode(), emptyModes.getMessage());
    }

    @Test
    void newerVersion_isRejectedNotSilentlyDowngraded() {
        ProvisionerException v3 = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(3,
                        "name", "future", "sizeMb", "10",
                        "modes", "ReadWriteOnce", "storageClass", "c",
                        "reclaim", "Retain", "quantum", "entangled")));
        assertEquals(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION, v3.getCode());
        log.info("case=v3-rejected code={} message='{}'", v3.getCode(), v3.getMessage());

        ProvisionerException v99 = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(99, "name", "x")));
        assertEquals(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION, v99.getCode());
        log.info("case=v99-rejected code={} message='{}'", v99.getCode(), v99.getMessage());

        ProvisionerException v0 = assertThrows(ProvisionerException.class,
                () -> upgrader.upgrade(record(0, "name", "x")));
        assertEquals(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION, v0.getCode());
        log.info("case=v0-rejected code={} message='{}'", v0.getCode(), v0.getMessage());
    }
}
