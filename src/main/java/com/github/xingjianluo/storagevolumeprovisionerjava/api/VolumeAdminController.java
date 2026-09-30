package com.github.xingjianluo.storagevolumeprovisionerjava.api;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.LegacyRecordRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ReclaimPolicyRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeView;
import com.github.xingjianluo.storagevolumeprovisionerjava.legacy.LegacyVolumeMigrator;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ReclaimService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeService;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;
import org.springframework.web.bind.annotation.*;

/** 管理面入口：卷生命周期管理与旧格式记录导入升级。 */
@RestController
@RequestMapping("/api/admin/volumes")
public class VolumeAdminController {

    private final ReclaimService reclaimService;
    private final VolumeService volumeService;
    private final LegacyVolumeMigrator migrator;
    private final VolumeStore volumeStore;
    private final LocalStorageBackend storageBackend;

    public VolumeAdminController(ReclaimService reclaimService,
                                 VolumeService volumeService,
                                 LegacyVolumeMigrator migrator,
                                 VolumeStore volumeStore,
                                 LocalStorageBackend storageBackend) {
        this.reclaimService = reclaimService;
        this.volumeService = volumeService;
        this.migrator = migrator;
        this.volumeStore = volumeStore;
        this.storageBackend = storageBackend;
    }

    @DeleteMapping("/{name}")
    public OperationResponse deleteVolume(@PathVariable String name) {
        return reclaimService.deleteVolume(name);
    }

    @PostMapping("/{name}/recycle-available")
    public VolumeView releaseToAvailable(@PathVariable String name) {
        return reclaimService.releaseToAvailable(name);
    }

    @PostMapping("/{name}/reclaim-policy")
    public VolumeView updateReclaimPolicy(@PathVariable String name, @RequestBody ReclaimPolicyRequest request) {
        return reclaimService.updateReclaimPolicy(name, request);
    }

    @PostMapping("/legacy/import")
    public OperationResponse importLegacy(@RequestBody LegacyRecordRequest request) {
        if (request == null || request.recordJson() == null || request.recordJson().isBlank()) {
            throw new com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException(
                    com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode.LEGACY_FIELD_MISSING,
                    "recordJson 不能为空");
        }
        var volume = migrator.migrate(request.recordJson());
        storageBackend.createDevice(volume.getName(), volume.getCapacityBytes());
        boolean registered = false;
        try {
            volumeStore.put(volume);
            registered = true;
        } finally {
            if (!registered && storageBackend.deviceExists(volume.getName())) {
                storageBackend.deleteDevice(volume.getName());
            }
        }
        return OperationResponse.ok("UPGRADED",
                "旧记录已确定性升级到格式 v" + LegacyVolumeMigrator.CURRENT_FORMAT_VERSION
                        + "：" + volume.getName(), VolumeView.of(volume));
    }
}
