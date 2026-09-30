package com.github.xingjianluo.storagevolumeprovisionerjava.storage;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地存储后端：用内存数据块模拟真实后端存储上的数据。
 *
 * <p>每个卷的数据是一份 {@code Map<String,String>}，每个快照保存某卷数据的深拷贝。
 * 所有操作在单一内部锁上串行化，保证：
 * <ul>
 *   <li>读写互斥，读不到半成品数据；</li>
 *   <li>快照恢复走"准备阶段（拷贝到暂存区）-> 提交阶段（整体替换）"两段式，
 *       只有准备全部成功才提交，提交本身是引用切换（不会中途失败）；
 *       准备阶段任何异常都直接抛出，卷数据保持原样，实现整体回滚。</li>
 * </ul>
 */
public class LocalStorageBackend {

    private final ConcurrentMap<String, Map<String, String>> volumeData = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, String>> snapshotData = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final AtomicBoolean failNextSnapshotOperation = new AtomicBoolean(false);

    public LocalStorageBackend() {
    }

    /** 写入（整体覆盖）卷的数据块，内部做防御性拷贝。 */
    public void writeData(String volumeName, Map<String, String> data) {
        synchronized (lock) {
            volumeData.put(volumeName, deepCopy(data));
        }
    }

    /** 读取卷数据的不可变拷贝；卷尚无数据时返回空 Map。 */
    public Map<String, String> readData(String volumeName) {
        synchronized (lock) {
            Map<String, String> current = volumeData.get(volumeName);
            return current == null ? Map.of() : Map.copyOf(current);
        }
    }

    /** 删除卷的全部数据及其引用（快照数据不受影响）。 */
    public void deleteData(String volumeName) {
        synchronized (lock) {
            volumeData.remove(volumeName);
        }
    }

    /** 卷是否在后端有数据块。 */
    public boolean exists(String volumeName) {
        synchronized (lock) {
            return volumeData.containsKey(volumeName);
        }
    }

    /** 初始化空数据块（卷注册时调用，幂等）。 */
    public void initData(String volumeName) {
        synchronized (lock) {
            volumeData.putIfAbsent(volumeName, new HashMap<>());
        }
    }

    /**
     * 为卷创建数据快照：深拷贝当前数据存到快照区。
     * 在拷贝完成后模拟可能发生的提交失败（故障注入），此时尚未写入快照区，对外无任何影响。
     */
    public void createSnapshotData(String volumeName, String snapshotName) {
        synchronized (lock) {
            Map<String, String> source = volumeData.get(volumeName);
            if (source == null) {
                throw new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "no data block for volume: " + volumeName);
            }
            // 准备阶段：在暂存区构建快照内容
            Map<String, String> staged = deepCopy(source);
            if (failNextSnapshotOperation.compareAndSet(true, false)) {
                throw new ProvisionerException(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE,
                        "injected failure during snapshot creation for: " + snapshotName);
            }
            // 提交阶段：引用放入，快照区原有内容被整体覆盖
            snapshotData.put(snapshotName, staged);
        }
    }

    /**
     * 用快照数据整体恢复卷。
     * 准备阶段在暂存区构建新数据；注入故障在提交前触发，此时卷数据完全未动，实现回滚。
     * 提交为引用切换，对外表现为"要么还是旧数据，要么就是完整新数据"。
     */
    public void restoreSnapshotData(String volumeName, String snapshotName) {
        synchronized (lock) {
            Map<String, String> snap = snapshotData.get(snapshotName);
            if (snap == null) {
                throw new ProvisionerException(ProvisionerErrorCode.SNAPSHOT_NOT_FOUND,
                        "snapshot not found in backend: " + snapshotName);
            }
            if (!volumeData.containsKey(volumeName)) {
                throw new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "no data block for volume: " + volumeName);
            }
            // 准备阶段：深拷贝到暂存区，旧数据保持不变
            Map<String, String> staged = deepCopy(snap);
            if (failNextSnapshotOperation.compareAndSet(true, false)) {
                throw new ProvisionerException(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE,
                        "injected failure before commit while restoring snapshot: " + snapshotName);
            }
            // 提交阶段：一次引用切换，原子生效
            volumeData.put(volumeName, staged);
        }
    }

    /** 删除快照数据。 */
    public void deleteSnapshotData(String snapshotName) {
        synchronized (lock) {
            snapshotData.remove(snapshotName);
        }
    }

    /**
     * 故障注入：置为 true 后，下一次快照创建/恢复在提交阶段前抛错，
     * 用于验证原子性与回滚。触发一次后自动复位。
     */
    public void setFailNextSnapshotOperation(boolean fail) {
        failNextSnapshotOperation.set(fail);
    }

    private static Map<String, String> deepCopy(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>();
        if (source != null) {
            for (Map.Entry<String, String> e : source.entrySet()) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return copy;
    }
}
