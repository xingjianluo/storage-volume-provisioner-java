package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.DataRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeView;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import com.github.xingjianluo.storagevolumeprovisionerjava.storage.LocalStorageBackend;
import com.github.xingjianluo.storagevolumeprovisionerjava.store.VolumeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/** 卷的供给、查询与数据读写。 */
@Service
public class VolumeService {

    private static final Logger log = LoggerFactory.getLogger(VolumeService.class);

    private final VolumeStore volumeStore;
    private final LocalStorageBackend storageBackend;

    public VolumeService(VolumeStore volumeStore, LocalStorageBackend storageBackend) {
        this.volumeStore = volumeStore;
        this.storageBackend = storageBackend;
    }

    public VolumeView provision(VolumeRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "卷名称不能为空");
        }
        long capacity = request.capacityBytes() == null ? -1L : request.capacityBytes();
        if (capacity <= 0) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "capacityBytes 必须为正数");
        }
        EnumSet<AccessMode> modes = RequestParsers.parseAccessModes(request.accessModes());
        String storageClass = RequestParsers.normalizeStorageClass(request.storageClassName());
        ReclaimPolicy policy = RequestParsers.parseReclaimPolicy(request.reclaimPolicy(), ReclaimPolicy.DELETE);

        log.info("供给卷输入: name={}, capacityBytes={}, accessModes={}, storageClass={}, reclaimPolicy={}",
                request.name(), capacity, modes, storageClass, policy);

        PersistentVolume volume = new PersistentVolume(request.name(), capacity, modes, storageClass, policy);
        storageBackend.createDevice(request.name(), capacity);
        boolean registered = false;
        try {
            volumeStore.put(volume);
            registered = true;
        } finally {
            if (!registered && storageBackend.deviceExists(request.name())) {
                storageBackend.deleteDevice(request.name());
            }
        }
        log.info("供给卷完成: name={}, state={}", volume.getName(), volume.getState());
        return VolumeView.of(volume);
    }

    public VolumeView get(String name) {
        return VolumeView.of(volumeStore.get(name));
    }

    public List<VolumeView> list() {
        return volumeStore.findAll().stream().map(VolumeView::of).toList();
    }

    public void writeData(String volumeName, DataRequest request) {
        PersistentVolume volume = volumeStore.get(volumeName);
        if (request == null || request.base64Data() == null) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "base64Data 不能为空");
        }
        byte[] data = Base64.getDecoder().decode(request.base64Data());
        log.info("写数据输入: volume={}, state={}, offset={}, dataLen={}",
                volumeName, volume.getState(), request.offset(), data.length);
        storageBackend.write(volumeName, request.offset(), data);
        log.info("写数据完成: volume={}, offset={}, written={}", volumeName, request.offset(), data.length);
    }

    public String readData(String volumeName, long offset, int length) {
        volumeStore.get(volumeName);
        byte[] data = storageBackend.read(volumeName, offset, length);
        return Base64.getEncoder().encodeToString(data);
    }
}
