package com.github.xingjianluo.storagevolumeprovisionerjava.service;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 请求入参解析：把字符串/枚举名称统一解析为领域枚举，非法值给出可区分原因。
 */
final class RequestParsers {

    private static final Map<String, AccessMode> ACCESS_ALIASES = Map.of(
            "RWO", AccessMode.RWO,
            "READWRITEONCE", AccessMode.RWO,
            "ROX", AccessMode.ROX,
            "READONLYMANY", AccessMode.ROX,
            "RWX", AccessMode.RWX,
            "READWRITEMANY", AccessMode.RWX);

    private RequestParsers() {
    }

    static AccessMode parseAccessMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "访问模式不能为空");
        }
        AccessMode mode = ACCESS_ALIASES.get(raw.trim().toUpperCase(Locale.ROOT));
        if (mode == null) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "无法识别的访问模式：" + raw);
        }
        return mode;
    }

    static EnumSet<AccessMode> parseAccessModes(List<String> rawModes) {
        if (rawModes == null || rawModes.isEmpty()) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT, "至少需要一个访问模式");
        }
        EnumSet<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        for (String raw : rawModes) {
            modes.add(parseAccessMode(raw));
        }
        return modes;
    }

    static ReclaimPolicy parseReclaimPolicy(String raw, ReclaimPolicy defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return ReclaimPolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ProvisioningException(ReasonCode.INVALID_ARGUMENT,
                    "无法识别的回收策略：" + raw + "（合法值：DELETE/RETAIN/RECYCLE）");
        }
    }

    static String normalizeStorageClass(String raw) {
        // null/空 表示不指定存储类；统一规整为 null，匹配时 null 只与 null 相等
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
