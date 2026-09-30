package com.github.xingjianluo.storagevolumeprovisionerjava.model;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 持久卷（PersistentVolume）。
 *
 * <p>每个 PV 内部持有一把锁（在实例上 synchronized），绑定/回收等涉及状态流转的操作
 * 必须在锁内完成，以保证同一块卷在任何并发情况下不会被重复绑定。</p>
 *
 * <p>合法状态流转（其它一律拒绝，绝不静默忽略）：</p>
 * <pre>
 *   AVAILABLE -> BOUND       被某个 PVC 绑定
 *   BOUND     -> RELEASED    绑定的 PVC 被删除
 *   RELEASED  -> AVAILABLE   Retain 人工放行 / Recycle 清空完成
 *   RELEASED  -> FAILED      回收（删除/清空）失败
 *   FAILED    -> RELEASED    人工复位后重试
 * </pre>
 * 若采用 Delete 策略且删除成功，则卷被直接从注册表移除，不产生新状态。
 */
public class PersistentVolume {

    private static final Map<VolumeState, Set<VolumeState>> ALLOWED_TRANSITIONS;

    static {
        Map<VolumeState, Set<VolumeState>> m = new EnumMap<>(VolumeState.class);
        m.put(VolumeState.AVAILABLE, EnumSet.of(VolumeState.BOUND));
        m.put(VolumeState.BOUND, EnumSet.of(VolumeState.RELEASED));
        m.put(VolumeState.RELEASED, EnumSet.of(VolumeState.AVAILABLE, VolumeState.FAILED));
        m.put(VolumeState.FAILED, EnumSet.of(VolumeState.RELEASED));
        ALLOWED_TRANSITIONS = Map.copyOf(m);
    }

    private final String name;
    private long capacityBytes;
    private final EnumSet<AccessMode> accessModes;
    private final String storageClassName;
    private ReclaimPolicy reclaimPolicy;
    private volatile VolumeState state = VolumeState.AVAILABLE;
    private volatile String claimRef;
    private volatile long version;

    public PersistentVolume(String name,
                            long capacityBytes,
                            EnumSet<AccessMode> accessModes,
                            String storageClassName,
                            ReclaimPolicy reclaimPolicy) {
        this.name = Objects.requireNonNull(name, "volume name");
        this.capacityBytes = capacityBytes;
        this.accessModes = Objects.requireNonNull(accessModes, "accessModes");
        this.storageClassName = storageClassName;
        this.reclaimPolicy = Objects.requireNonNull(reclaimPolicy, "reclaimPolicy");
    }

    public String getName() {
        return name;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public void setCapacityBytes(long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public EnumSet<AccessMode> getAccessModes() {
        return accessModes;
    }

    public String getStorageClassName() {
        return storageClassName;
    }

    public ReclaimPolicy getReclaimPolicy() {
        return reclaimPolicy;
    }

    public VolumeState getState() {
        return state;
    }

    public String getClaimRef() {
        return claimRef;
    }

    public long getVersion() {
        return version;
    }

    /** 仅 AVAILABLE 状态允许修改回收策略，其它状态拒绝（避免语义在使用中被改变）。 */
    public synchronized void changeReclaimPolicy(ReclaimPolicy newPolicy) {
        if (state != VolumeState.AVAILABLE) {
            throw new ProvisioningException(ReasonCode.RECLAIM_POLICY_CHANGE_REJECTED,
                    "卷 " + name + " 当前状态=" + state + "，只有 AVAILABLE 才能修改回收策略");
        }
        this.reclaimPolicy = Objects.requireNonNull(newPolicy, "newPolicy");
        this.version++;
    }

    /**
     * 原子绑定：仅当卷仍为 AVAILABLE 时才能成功。
     *
     * @return true 表示本次调用完成绑定；false 表示卷已被他人绑定（绝不重复绑定）
     */
    public synchronized boolean tryBind(String claimName) {
        if (state != VolumeState.AVAILABLE) {
            return false;
        }
        this.state = VolumeState.BOUND;
        this.claimRef = Objects.requireNonNull(claimName, "claimName");
        this.version++;
        return true;
    }

    /**
     * 并发回滚：tryBind 预留成功后，若发现请求侧已被其他线程绑定，
     * 把卷从 BOUND 安全退回 AVAILABLE。仅允许在引用仍是该 claim 时回滚。
     * 这是绑定协议的内部补偿动作，不属于用户可见的生命周期流转。
     */
    public synchronized void rollbackBind(String expectedClaim) {
        if (state == VolumeState.BOUND && expectedClaim.equals(claimRef)) {
            this.state = VolumeState.AVAILABLE;
            this.claimRef = null;
            this.version++;
        }
    }

    /** BOUND -> RELEASED 并解除引用；要求引用确实是该 claim。 */
    public synchronized void markReleased(String expectedClaim) {
        if (state != VolumeState.BOUND) {
            throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                    name + " 当前状态=" + state + "，只有 BOUND 才能变为 RELEASED");
        }
        if (claimRef != null && !claimRef.equals(expectedClaim)) {
            throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                    name + " 绑定引用=" + claimRef + " 与请求=" + expectedClaim + " 不一致");
        }
        transitionTo(VolumeState.RELEASED);
        this.claimRef = null;
    }

    /** RELEASED -> AVAILABLE（Retain 人工放行 / Recycle 清空完成）。 */
    public synchronized void markAvailable() {
        transitionTo(VolumeState.AVAILABLE);
        this.claimRef = null;
    }

    /** RELEASED -> FAILED。 */
    public synchronized void markFailed() {
        transitionTo(VolumeState.FAILED);
    }

    /** FAILED -> RELEASED（人工复位，准备重试回收）。 */
    public synchronized void markReleasedFromFailed() {
        transitionTo(VolumeState.RELEASED);
    }

    private void transitionTo(VolumeState target) {
        Set<VolumeState> allowed = ALLOWED_TRANSITIONS.getOrDefault(state, Set.of());
        if (!allowed.contains(target)) {
            throw new ProvisioningException(ReasonCode.ILLEGAL_STATE_TRANSITION,
                    "卷 " + name + " 不允许 " + state + " -> " + target
                            + "（允许的目标状态=" + allowed + "）");
        }
        VolumeState before = this.state;
        this.state = target;
        this.version++;
        // 状态流转集中记录判定依据
        System.out.printf("[volume-state] %s: %s -> %s (v%d)%n", name, before, target, version);
    }
}
