package com.github.xingjianluo.storagevolumeprovisionerjava.storage;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用本地内存模拟后端存储设备。
 *
 * <p>每块卷对应一个固定容量的字节数组。写超容量会抛出异常，
 * 从而让容量、数据写入、快照复制/恢复都有确定性的本地行为。</p>
 *
 * <p>另外提供可注入的故障钩子，用于测试“快照恢复中途失败必须整体回滚”。</p>
 */
@Component
public class LocalStorageBackend {

    /** 在下一次存储写操作上注入故障（用于测试恢复中途失败）。 */
    @FunctionalInterface
    public interface FailureHook {
        void beforeWrite(String volumeName, long offset, int length);
    }

    /** 恢复时逐块写入的块大小，钩子在每块写入前触发，可模拟“写到一半失败”。 */
    static final int RESTORE_CHUNK = 64;

    private final Map<String, byte[]> devices = new ConcurrentHashMap<>();
    private volatile FailureHook failureHook;

    public void createDevice(String volumeName, long capacityBytes) {
        if (capacityBytes <= 0 || capacityBytes > Integer.MAX_VALUE) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                    "容量必须在 (0, " + Integer.MAX_VALUE + "] 字节之间，实际=" + capacityBytes);
        }
        byte[] device = new byte[(int) capacityBytes];
        if (devices.putIfAbsent(volumeName, device) != null) {
            throw new ProvisioningException(ReasonCode.ALREADY_EXISTS, "存储设备已存在：" + volumeName);
        }
    }

    public boolean deviceExists(String volumeName) {
        return devices.containsKey(volumeName);
    }

    public void deleteDevice(String volumeName) {
        if (devices.remove(volumeName) == null) {
            throw new ProvisioningException(ReasonCode.VOLUME_NOT_FOUND, "存储设备不存在：" + volumeName);
        }
    }

    public long getCapacity(String volumeName) {
        return requireDevice(volumeName).length;
    }

    /** 读取 [offset, offset+length)，返回防御性拷贝；越界即非法参数。 */
    public byte[] read(String volumeName, long offset, int length) {
        byte[] device = requireDevice(volumeName);
        if (offset < 0 || length < 0 || offset + length > device.length) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                    String.format("读取越界：offset=%d, length=%d, capacity=%d", offset, length, device.length));
        }
        byte[] out = new byte[length];
        System.arraycopy(device, (int) offset, out, 0, length);
        return out;
    }

    /** 普通数据写入：一次性写入，超容量拒绝。 */
    public void write(String volumeName, long offset, byte[] data) {
        byte[] device = requireDevice(volumeName);
        if (offset < 0 || data == null || offset + data.length > device.length) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                    String.format("写入越界：offset=%d, dataLen=%d, capacity=%d",
                            offset, data == null ? -1 : data.length, device.length));
        }
        System.arraycopy(data, 0, device, (int) offset, data.length);
    }

    /** 读取整盘内容，返回防御性拷贝（快照使用）。 */
    public byte[] snapshotRead(String volumeName) {
        byte[] device = requireDevice(volumeName);
        return Arrays.copyOf(device, device.length);
    }

    /**
     * 整体写入（快照恢复）：长度必须与容量完全一致，逐块写入，
     * 每块写入前调用故障钩子。调用方需保证在失败时用备份回滚。
     */
    public void restoreAll(String volumeName, byte[] data) {
        byte[] device = requireDevice(volumeName);
        if (data == null || data.length != device.length) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                    "恢复数据长度必须与卷容量完全一致");
        }
        FailureHook hook = this.failureHook;
        int written = 0;
        while (written < data.length) {
            int chunk = Math.min(RESTORE_CHUNK, data.length - written);
            if (hook != null) {
                hook.beforeWrite(volumeName, written, chunk);
            }
            System.arraycopy(data, written, device, written, chunk);
            written += chunk;
        }
    }

    public void setFailureHook(FailureHook hook) {
        this.failureHook = hook;
    }

    public void clearFailureHook() {
        this.failureHook = null;
    }

    private byte[] requireDevice(String volumeName) {
        byte[] device = devices.get(volumeName);
        if (device == null) {
            throw new ProvisioningException(ReasonCode.VOLUME_NOT_FOUND, "存储设备不存在：" + volumeName);
        }
        return device;
    }
}
