package com.github.xingjianluo.storagevolumeprovisionerjava.compat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧格式卷记录的原始表示。
 *
 * <p>历史上存在两种磁盘格式：
 * <ul>
 *   <li>v1：扁平字符串字段，{@code size} 形如 "10Gi"，{@code mode} 为 RO/RW/RWM，
 *       无 storageClass（按 default 处理），无 reclaim（按 Retain 处理）。</li>
 *   <li>v2：容量为 {@code sizeMb} 数值，访问模式为 K8s 风格
 *       ReadOnlyMany / ReadWriteOnce / ReadWriteMany，带 storageClass 与 reclaim 字段。</li>
 * </ul>
 *
 * <p>本类只承载解析后的字段值；版本判定、缺失/非法校验、确定性升级由
 * {@link LegacyRecordUpgrader} 完成。
 */
public final class LegacyVolumeRecord {

    private final int formatVersion;
    private final Map<String, String> fields;

    public LegacyVolumeRecord(int formatVersion, Map<String, String> fields) {
        this.formatVersion = formatVersion;
        this.fields = fields == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(fields);
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public Map<String, String> getFields() {
        return new LinkedHashMap<>(fields);
    }

    public String get(String key) {
        return fields.get(key);
    }

    @Override
    public String toString() {
        return "LegacyVolumeRecord{formatVersion=" + formatVersion + ", fields=" + fields + "}";
    }
}
