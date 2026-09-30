package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.compat.LegacyRecordUpgrader;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 供给器组件装配。
 */
@Configuration
public class ProvisionerConfiguration {

    @Bean
    public LocalStorageBackend localStorageBackend() {
        return new LocalStorageBackend();
    }

    @Bean
    public LegacyRecordUpgrader legacyRecordUpgrader() {
        return new LegacyRecordUpgrader();
    }

    @Bean
    public VolumeRegistry volumeRegistry() {
        return new VolumeRegistry();
    }

    @Bean
    public ProvisioningService provisioningService(VolumeRegistry registry,
                                                   LocalStorageBackend storageBackend) {
        return new ProvisioningService(registry, storageBackend);
    }

    @Bean
    public LifecycleService lifecycleService(VolumeRegistry registry,
                                             LocalStorageBackend storageBackend) {
        return new LifecycleService(registry, storageBackend);
    }

    @Bean
    public SnapshotService snapshotService(VolumeRegistry registry,
                                           LocalStorageBackend storageBackend) {
        return new SnapshotService(registry, storageBackend);
    }
}
