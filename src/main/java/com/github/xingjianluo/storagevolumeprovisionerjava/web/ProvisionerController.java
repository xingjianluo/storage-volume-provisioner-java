package com.github.xingjianluo.storagevolumeprovisionerjava.web;

import com.github.xingjianluo.storagevolumeprovisionerjava.compat.LegacyRecordUpgrader;
import com.github.xingjianluo.storagevolumeprovisionerjava.compat.LegacyVolumeRecord;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.RequestStatus;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.VolumeState;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.LifecycleService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ProvisioningService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.SnapshotService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地存储卷供给与绑定的 REST 入口。
 *
 * <pre>
 * POST   /api/volumes                         注册（供给）一个卷
 * GET    /api/volumes                         列出全部卷及状态
 * GET    /api/volumes/{name}                  查看单个卷
 * DELETE /api/volumes/{name}                  删除卷（BOUND 拒绝）
 * POST   /api/claims                          创建存储请求并立即尝试绑定
 * GET    /api/claims/{name}                   查看请求状态（含 PENDING 原因代码）
 * POST   /api/claims/{name}/bind              再次触发自动匹配
 * POST   /api/claims/{name}/bind/{volume}     显式绑定到指定卷
 * POST   /api/claims/{name}/release           释放（按卷回收策略流转）
 * POST   /api/volumes/{name}/reuse            Retain 卷人工复用
 * PUT    /api/volumes/{name}/data             写入卷数据
 * GET    /api/volumes/{name}/data             读取卷数据
 * POST   /api/volumes/{name}/snapshots        创建快照
 * GET    /api/volumes/{name}/snapshots        列出快照
 * POST   /api/volumes/{name}/snapshots/{snap}/restore  从快照恢复（原子）
 * POST   /api/legacy/upgrade                  升级旧格式记录
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class ProvisionerController {

    private final ProvisioningService provisioningService;
    private final LifecycleService lifecycleService;
    private final SnapshotService snapshotService;
    private final LegacyRecordUpgrader upgrader;
    private final VolumeRegistry registry;

    public ProvisionerController(ProvisioningService provisioningService,
                                 LifecycleService lifecycleService,
                                 SnapshotService snapshotService,
                                 LegacyRecordUpgrader upgrader,
                                 VolumeRegistry registry) {
        this.provisioningService = provisioningService;
        this.lifecycleService = lifecycleService;
        this.snapshotService = snapshotService;
        this.upgrader = upgrader;
        this.registry = registry;
    }

    // ---- 卷供给 ----

    @PostMapping("/volumes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerVolume(@RequestBody Dtos.RegisterVolumeRequest body) {
        ReclaimPolicy policy = body.reclaimPolicy() == null
                ? ReclaimPolicy.RETAIN
                : parseReclaim(body.reclaimPolicy());
        PersistentVolume volume = new PersistentVolume(
                requireText(body.name(), "name"),
                requirePositive(body.capacityBytes(), "capacityBytes"),
                parseModes(body.accessModes()),
                body.storageClass(),
                policy);
        provisioningService.registerVolume(volume);
        return volumeView(volume);
    }

    @GetMapping("/volumes")
    public List<Map<String, Object>> listVolumes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PersistentVolume v : registry.listVolumes()) {
            out.add(volumeView(v));
        }
        return out;
    }

    @GetMapping("/volumes/{name}")
    public Map<String, Object> getVolume(@PathVariable String name) {
        return volumeView(registry.findVolume(name).orElseThrow(() ->
                new ProvisionerException(ProvisionerErrorCode.VOLUME_NOT_FOUND,
                        "volume not found: " + name)));
    }

    @DeleteMapping("/volumes/{name}")
    public ResponseEntity<Void> deleteVolume(@PathVariable String name) {
        lifecycleService.deleteVolume(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/volumes/{name}/reuse")
    public Map<String, Object> reuse(@PathVariable String name) {
        return volumeView(lifecycleService.reuseRetainedVolume(name));
    }

    // ---- 数据面 ----

    @PutMapping("/volumes/{name}/data")
    public Map<String, Object> writeData(@PathVariable String name,
                                         @RequestBody Dtos.WriteDataRequest body) {
        Map<String, String> data = body.data() == null ? Map.of() : body.data();
        snapshotService.writeVolumeData(name, data);
        return Map.of("volume", name, "keys", data.keySet());
    }

    @GetMapping("/volumes/{name}/data")
    public Map<String, String> readData(@PathVariable String name) {
        return snapshotService.readVolumeData(name);
    }

    // ---- 快照 ----

    @PostMapping("/volumes/{name}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSnapshot(@PathVariable String name,
                                              @RequestBody(required = false)
                                              Dtos.CreateSnapshotRequest body) {
        String snapshotName = body == null || body.snapshotName() == null
                ? name + "-snap-" + System.nanoTime()
                : body.snapshotName();
        var snap = snapshotService.createSnapshot(name, snapshotName);
        return snapshotView(snap);
    }

    @GetMapping("/volumes/{name}/snapshots")
    public List<Map<String, Object>> listSnapshots(@PathVariable String name) {
        return snapshotService.listSnapshots(name).stream()
                .map(ProvisionerController::snapshotView)
                .toList();
    }

    @PostMapping("/volumes/{name}/snapshots/{snapshot}/restore")
    public Map<String, Object> restore(@PathVariable String name,
                                       @PathVariable("snapshot") String snapshot) {
        snapshotService.restoreSnapshot(name, snapshot);
        return Map.of("volume", name, "restoredFrom", snapshot,
                "data", snapshotService.readVolumeData(name));
    }

    // ---- 存储请求 / 绑定 ----

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createClaim(@RequestBody Dtos.RegisterClaimRequest body) {
        VolumeRequest request = new VolumeRequest(
                requireText(body.name(), "name"),
                requirePositive(body.capacityBytes(), "capacityBytes"),
                parseModes(body.accessModes()),
                body.storageClass());
        provisioningService.registerRequest(request);
        return statusView(body.name(),
                registry.getRequestStatus(body.name()).orElseThrow());
    }

    @GetMapping("/claims/{name}")
    public Map<String, Object> getClaim(@PathVariable String name) {
        RequestStatus status = registry.getRequestStatus(name).orElseThrow(() ->
                new ProvisionerException(ProvisionerErrorCode.REQUEST_NOT_FOUND,
                        "request not found: " + name));
        return statusView(name, status);
    }

    @PostMapping("/claims/{name}/bind")
    public Map<String, Object> rebind(@PathVariable String name) {
        return statusView(name, provisioningService.tryBind(name));
    }

    @PostMapping("/claims/{name}/bind/{volume}")
    public Map<String, Object> bindTo(@PathVariable String name,
                                      @PathVariable("volume") String volume) {
        return statusView(name, provisioningService.bindToVolume(name, volume));
    }

    @PostMapping("/claims/{name}/release")
    public Map<String, Object> release(@PathVariable String name) {
        VolumeState state = lifecycleService.release(name);
        return statusView(name, registry.getRequestStatus(name).orElseThrow(),
                state == null ? "REMOVED" : state.name());
    }

    // ---- 旧格式升级 ----

    @PostMapping("/legacy/upgrade")
    public Map<String, Object> upgradeLegacy(@RequestBody Map<String, Object> body) {
        Object v = body.get("version");
        if (!(v instanceof Number n)) {
            throw new ProvisionerException(ProvisionerErrorCode.LEGACY_MISSING_FIELD,
                    "legacy record requires numeric 'version'");
        }
        int version = n.intValue();
        Map<String, String> fields = new LinkedHashMap<>();
        Object rawFields = body.get("fields");
        if (rawFields instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                fields.put(String.valueOf(e.getKey()),
                        e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        LegacyVolumeRecord record = new LegacyVolumeRecord(version, fields);
        PersistentVolume upgraded = upgrader.upgrade(record);
        return volumeView(upgraded);
    }

    // ---- 视图与解析 ----

    static Map<String, Object> volumeView(PersistentVolume v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", v.getName());
        m.put("capacityBytes", v.getCapacityBytes());
        m.put("supportedModes", v.getSupportedModes().stream().map(Enum::name).sorted().toList());
        m.put("storageClass", v.getStorageClass());
        m.put("reclaimPolicy", v.getReclaimPolicy().name());
        m.put("state", v.getState().name());
        m.put("boundRequestName", v.getBoundRequestName());
        m.put("lastOperation", v.getLastOperation());
        m.put("failureReason", v.getFailureReason());
        return m;
    }

    static Map<String, Object> snapshotView(com.github.xingjianluo.storagevolumeprovisionerjava.model.Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("sourceVolumeName", s.getSourceVolumeName());
        m.put("createdAtEpochNanos", s.getCreatedAtEpochMillis());
        return m;
    }

    private Map<String, Object> statusView(String name, RequestStatus status) {
        return statusView(name, status, null);
    }

    private Map<String, Object> statusView(String name, RequestStatus status, String volumeResult) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request", name);
        m.put("phase", status.getPhase().name());
        m.put("reason", status.getReason() == null ? null : status.getReason().name());
        m.put("boundVolumeName", status.getBoundVolumeName());
        m.put("detail", status.getDetail());
        if (volumeResult != null) {
            m.put("volumeResult", volumeResult);
        }
        return m;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    field + " must not be blank");
        }
        return value;
    }

    private static long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    field + " must be a positive number");
        }
        return value;
    }

    private static EnumSet<AccessMode> parseModes(List<String> modes) {
        if (modes == null || modes.isEmpty()) {
            throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                    "accessModes must contain at least one of ROX/RWO/RWX");
        }
        EnumSet<AccessMode> set = EnumSet.noneOf(AccessMode.class);
        for (String token : modes) {
            try {
                set.add(AccessMode.valueOf(token.trim()));
            } catch (IllegalArgumentException e) {
                throw new ProvisionerException(ProvisionerErrorCode.INVALID_REQUEST,
                        "unknown access mode: '" + token + "'");
            }
        }
        return set;
    }

    private static ReclaimPolicy parseReclaim(String token) {
        try {
            return ReclaimPolicy.valueOf(token.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProvisionerException(ProvisionerErrorCode.UNKNOWN_RECLAIM_POLICY,
                    "unknown reclaim policy: '" + token + "'");
        }
    }
}
