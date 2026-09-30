package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.DataRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.RestoreRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.ServiceHarness;
import com.github.xingjianluo.storagevolumeprovisionerjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重点测试：快照恢复的原子性。
 * 中途失败必须整体回滚，不能留下半新半旧的数据。
 */
class SnapshotRestoreTest {

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private byte[] readAll(ServiceHarness h, int len) {
        return Base64.getDecoder().decode(h.volumeService().readData("v", 0, len));
    }

    private ServiceHarness provisionedVolume(int cap) {
        ServiceHarness h = ServiceHarness.create();
        h.volumeService().provision(new VolumeRequest("v", (long) cap, List.of("RWO"), "ssd", "RETAIN"));
        return h;
    }

    @Test
    void snapshotThenRestore_appliesSnapshotWholly() {
        ServiceHarness h = provisionedVolume(256);
        // 旧数据 A
        byte[] oldData = new byte[256];
        Arrays.fill(oldData, (byte) 'A');
        h.volumeService().writeData("v", new DataRequest(0, b64(oldData)));

        h.snapshotService().createSnapshot("v", "snap-a");
        TestLog.state("创建快照", "snap-a 保存全 A 数据");

        // 改成新数据 B
        byte[] newData = new byte[256];
        Arrays.fill(newData, (byte) 'B');
        h.volumeService().writeData("v", new DataRequest(0, b64(newData)));
        assertArrayEquals(newData, readAll(h, 256));

        OperationResponse resp = h.snapshotService().restoreSnapshot("snap-a", new RestoreRequest("v"));
        TestLog.state("恢复结果", resp.action());
        assertEquals("RESTORED", resp.action());
        assertArrayEquals(oldData, readAll(h, 256));
        TestLog.assert_("整体恢复成功", "卷内容回到快照（全 A）");
    }

    @Test
    void restoreFailureMidway_rollsBackCompletely_noHalfNewHalfOld() {
        int cap = 256;
        ServiceHarness h = provisionedVolume(cap);

        // 当前数据全为旧值 O
        byte[] current = new byte[cap];
        Arrays.fill(current, (byte) 'O');
        h.volumeService().writeData("v", new DataRequest(0, b64(current)));
        h.snapshotService().createSnapshot("v", "snap-old");

        // 期望恢复的新数据全为 N
        byte[] desired = new byte[cap];
        Arrays.fill(desired, (byte) 'N');
        h.volumeService().writeData("v", new DataRequest(0, b64(desired)));
        // 在卷是 N 的状态下再打一个“目标快照”——实际我们恢复到 old(O)，
        // 为构造“中途失败”，直接恢复 snap-old，并在写到第二块时注入故障
        h.backend().setFailureHook((volumeName, offset, length) -> {
            if (offset >= 64) {
                throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "模拟存储设备中途故障@" + offset);
            }
        });

        TestLog.input("恢复中途故障注入点", "offset>=64 抛出异常（块大小64）");
        TestLog.basis("当前盘内容", "全 N；目标快照内容为全 O；期望失败后仍为全 N");

        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.snapshotService().restoreSnapshot("snap-old", new RestoreRequest("v")));
        h.backend().clearFailureHook();
        TestLog.state("恢复失败原因", ex.getReason());
        assertEquals(ReasonCode.SNAPSHOT_RESTORE_FAILED, ex.getReason());

        byte[] after = readAll(h, cap);
        assertArrayEquals(desired, after,
                "恢复中途失败必须整体回滚，卷内容应保持恢复前的全 N，不能半 N 半 O");
        TestLog.assert_("原子回滚", "256 字节全部保持恢复前内容 N，无半新半旧");

        // 回滚后卷仍然可用，可以正常恢复成功
        OperationResponse retry = h.snapshotService().restoreSnapshot("snap-old", new RestoreRequest("v"));
        assertEquals("RESTORED", retry.action());
        assertArrayEquals(current, readAll(h, cap));
        TestLog.assert_("故障清除后可重试成功", "卷内容变为快照 O");
    }

    @Test
    void restoreToDifferentVolume_isRejected() {
        ServiceHarness h = provisionedVolume(128);
        h.snapshotService().createSnapshot("v", "snap-x");
        h.volumeService().provision(new VolumeRequest("other", 128L, List.of("RWO"), "ssd", "RETAIN"));
        TestLog.input("非法恢复", "snap-x(属于v) -> other");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.snapshotService().restoreSnapshot("snap-x", new RestoreRequest("other")));
        assertEquals(ReasonCode.SNAPSHOT_VOLUME_MISMATCH, ex.getReason());
        TestLog.state("拒绝原因", ex.getReason());
    }

    @Test
    void restoreCapacityMismatch_isRejected() {
        ServiceHarness h = ServiceHarness.create();
        // 当前卷 256 字节
        h.volumeService().provision(new VolumeRequest("v2", 256L, List.of("RWO"), "ssd", "RETAIN"));
        // 模拟一张卷扩容前留下的历史快照：归属 v2，但容量只有 128
        h.snapshotStore().put(new com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot(
                "snap-oldsize", "v2", 128L, new byte[128], System.currentTimeMillis()));

        TestLog.input("容量不一致恢复", "快照=128 字节，卷 v2=256 字节");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> h.snapshotService().restoreSnapshot(
                        "snap-oldsize", new RestoreRequest("v2")));
        assertEquals(ReasonCode.SNAPSHOT_RESTORE_CAPACITY_MISMATCH, ex.getReason());
        TestLog.state("拒绝原因", ex.getReason());
    }
}
