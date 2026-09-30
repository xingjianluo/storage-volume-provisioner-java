package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeRequest;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

/**
 * 卷与请求的内存注册表，是全部绑定/流转操作的并发同步点。
 *
 * <p>并发模型：
 * <ul>
 *   <li>卷与请求分别存放在 {@link ConcurrentHashMap} 中。</li>
 *   <li>每个卷名对应一把稳定的监视器锁（{@link #volumeLock(String)}），
 *       "检查状态 + 流转状态"必须在同一把锁内完成，保证并发重复绑定时
 *       同一卷只会被一个线程成功绑定。</li>
 *   <li>跨卷扫描（tryBind）按卷名字典序依次加锁，避免多卷加锁顺序反转导致死锁。</li>
 *   <li>请求状态的更新通过 {@link #updateRequestStatus(String, RequestStatus)} 原子覆盖。</li>
 * </ul>
 */
public class VolumeRegistry {

    private final ConcurrentMap<String, PersistentVolume> volumes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, VolumeRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RequestStatus> statuses = new ConcurrentHashMap<>();
    /** 每个卷名一把锁，生命周期内稳定存在（卷数有限，无需驱逐）。 */
    private final ConcurrentMap<String, Object> volumeLocks = new ConcurrentHashMap<>();

    public VolumeRegistry() {
    }

    public void registerVolume(PersistentVolume volume) {
        if (volumes.putIfAbsent(volume.getName(), volume) != null) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "volume already exists: " + volume.getName());
        }
    }

    public void registerRequest(VolumeRequest request, RequestStatus initialStatus) {
        if (requests.putIfAbsent(request.getName(), request) != null) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "request already exists: " + request.getName());
        }
        statuses.put(request.getName(), initialStatus);
    }

    public Optional<PersistentVolume> findVolume(String name) {
        return Optional.ofNullable(volumes.get(name));
    }

    public Optional<VolumeRequest> findRequest(String name) {
        return Optional.ofNullable(requests.get(name));
    }

    public Optional<RequestStatus> getRequestStatus(String name) {
        return Optional.ofNullable(statuses.get(name));
    }

    public void updateRequestStatus(String name, RequestStatus status) {
        if (!requests.containsKey(name)) {
            throw new ProvisionerException(ProvisionerErrorCode.REQUEST_NOT_FOUND,
                    "request not found: " + name);
        }
        statuses.put(name, status);
    }

    public Collection<PersistentVolume> listVolumes() {
        return java.util.List.copyOf(volumes.values());
    }

    public Collection<VolumeRequest> listRequests() {
        return java.util.List.copyOf(requests.values());
    }

    /**
     * 从注册表移除卷（终态删除）。调用方必须已持有该卷锁并完成状态校验。
     */
    public PersistentVolume removeVolume(String name) {
        return volumes.remove(name);
    }

    public Map<String, RequestStatus> currentStatuses() {
        return Map.copyOf(statuses);
    }

    /**
     * 返回卷名对应的稳定锁对象。
     */
    public Object volumeLock(String volumeName) {
        return volumeLocks.computeIfAbsent(volumeName, k -> new Object());
    }

    /**
     * 在卷锁内读取卷并执行函数，返回函数结果。
     * 函数内可以安全地检查并修改该卷的状态（检查-执行原子性）。
     */
    public <T> T withVolumeLock(String volumeName, java.util.function.Function<PersistentVolume, T> action) {
        PersistentVolume volume = volumes.get(volumeName);
        if (volume == null) {
            throw new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                    "volume not found: " + volumeName);
        }
        synchronized (volumeLock(volumeName)) {
            return action.apply(volume);
        }
    }

    /**
     * 在卷锁内对卷执行原地修改操作（无返回值场景）。
     */
    public void mutateVolume(String volumeName, UnaryOperator<PersistentVolume> action) {
        withVolumeLock(volumeName, v -> {
            action.apply(v);
            return null;
        });
    }

    /**
     * 按卷名字典序依次获取多把卷锁，执行扫描操作。
     * 统一的加锁顺序保证多线程交叉绑定时不会死锁。
     */
    public <T> T withOrderedVolumeLocks(java.util.function.Function<Collection<PersistentVolume>, T> action) {
        var sorted = volumes.values().stream()
                .map(PersistentVolume::getName)
                .sorted()
                .map(this::volumeLock)
                .toList();
        return lockAll(sorted, 0, () -> action.apply(listVolumes()));
    }

    private <T> T lockAll(java.util.List<Object> locks, int index,
                          java.util.function.Supplier<T> body) {
        if (index == locks.size()) {
            return body.get();
        }
        synchronized (locks.get(index)) {
            return lockAll(locks, index + 1, body);
        }
    }
}
