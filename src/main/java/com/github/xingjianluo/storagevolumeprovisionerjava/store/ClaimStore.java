package com.github.xingjianluo.storagevolumeprovisionerjava.store;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolumeClaim;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PVC 注册表（内存实现，线程安全）。
 */
@Component
public class ClaimStore {

    private final ConcurrentHashMap<String, PersistentVolumeClaim> claims = new ConcurrentHashMap<>();

    public void put(PersistentVolumeClaim claim) {
        if (claims.putIfAbsent(claim.getName(), claim) != null) {
            throw new ProvisioningException(ReasonCode.ALREADY_EXISTS, "卷请求已存在：" + claim.getName());
        }
    }

    public Optional<PersistentVolumeClaim> find(String name) {
        return Optional.ofNullable(claims.get(name));
    }

    public PersistentVolumeClaim get(String name) {
        return find(name).orElseThrow(() ->
                new ProvisioningException(ReasonCode.CLAIM_NOT_FOUND, "卷请求不存在：" + name));
    }

    public List<PersistentVolumeClaim> findAll() {
        return claims.values().stream()
                .sorted(java.util.Comparator.comparing(PersistentVolumeClaim::getName))
                .toList();
    }

    public void remove(String name) {
        if (claims.remove(name) == null) {
            throw new ProvisioningException(ReasonCode.CLAIM_NOT_FOUND, "卷请求不存在：" + name);
        }
    }
}
