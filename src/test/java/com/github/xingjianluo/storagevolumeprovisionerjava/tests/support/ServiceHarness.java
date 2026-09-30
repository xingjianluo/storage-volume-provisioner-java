package com.github.xingjianluo.storagevolumeprovisionerjava.tests.support;

import com.github.xingjianluo.storagevolumeprovisionerjava.service.BindingService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ReclaimService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.SnapshotService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeService;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.ClaimStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.SnapshotStore;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;

/**
 * 纯手工装配一套相互隔离的服务实例（不启动 Spring），每个用例独立状态。
 */
public record ServiceHarness(
        LocalStorageBackend backend,
        VolumeStore volumeStore,
        ClaimStore claimStore,
        SnapshotStore snapshotStore,
        VolumeService volumeService,
        BindingService bindingService,
        ReclaimService reclaimService,
        SnapshotService snapshotService) {

    public static ServiceHarness create() {
        LocalStorageBackend backend = new LocalStorageBackend();
        VolumeStore volumeStore = new VolumeStore();
        ClaimStore claimStore = new ClaimStore();
        SnapshotStore snapshotStore = new SnapshotStore();
        VolumeService volumeService = new VolumeService(volumeStore, backend);
        BindingService bindingService = new BindingService(volumeStore, claimStore);
        ReclaimService reclaimService = new ReclaimService(volumeStore, claimStore, snapshotStore, backend);
        SnapshotService snapshotService = new SnapshotService(volumeStore, snapshotStore, backend);
        return new ServiceHarness(backend, volumeStore, claimStore, snapshotStore,
                volumeService, bindingService, reclaimService, snapshotService);
    }
}
