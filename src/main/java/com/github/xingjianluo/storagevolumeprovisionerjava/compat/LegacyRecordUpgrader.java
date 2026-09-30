package com.github.xingjianluo.storagevolumeprovisionerjava.compat;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 旧格式卷记录升级器：把 v1/v2 记录确定性升级为当前 {@link PersistentVolume} 模型。
 *
 * <p>格式约定：
 * <ul>
 *   <li><b>v1</b>：扁平字符串字段。
 *     <ul>
 *       <li>{@code name}：必填，卷名。</li>
 *       <li>{@code size}：必填，形如 {@code "10Gi"} / {@code "500Mi"} / {@code "1024Ki"}，
 *           按 1Gi=1024Mi、1Mi=1024Ki 换算为字节。</li>
 *       <li>{@code mode}：必填，单值 {@code RO}/{@code RW}/{@code RWM}。</li>
 *       <li>{@code storageClass}：缺省按 {@code "default"} 处理（v1 时代无此概念）。</li>
 *       <li>{@code reclaim}：缺省按 {@code RETAIN} 处理（旧数据默认保留）。</li>
 *     </ul>
 *   </li>
 *   <li><b>v2</b>：半结构化字段。
 *     <ul>
 *       <li>{@code name}：必填。</li>
 *       <li>{@code sizeMb}：必填，非负整数（兆字节，1Mi=1048576 字节）。</li>
 *       <li>{@code modes}：必填，逗号分隔的 K8s 风格访问模式，
 *           {@code ReadOnlyMany/ReadWriteOnce/ReadWriteMany}，至少一个合法值。</li>
 *       <li>{@code storageClass}：必填。</li>
 *       <li>{@code reclaim}：必填，{@code Delete/Retain/Recycle}。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>确定性保证：升级只依赖输入字段，不读取时间、随机数或环境状态；
 * 多值字段按枚举顺序排序后存入。同一条记录升级任意次结果完全一致。
 * 字段缺失抛 {@link ProvisionerErrorCode#LEGACY_MISSING_FIELD}，
 * 字段非法抛 {@link ProvisionerErrorCode#LEGACY_INVALID_FIELD}，
 * 版本高于 {@link #SUPPORTED_VERSION} 抛 {@link ProvisionerErrorCode#UNSUPPORTED_RECORD_VERSION}，
 * 绝不按旧记录静默猜测。
 */
public class LegacyRecordUpgrader {

    /** 当前软件支持的最高记录格式版本。 */
    public static final int SUPPORTED_VERSION = 2;

    /**
     * 把旧记录升级为当前卷模型。
     *
     * @param record 旧记录
     * @return 升级后的卷（状态为 AVAILABLE，lastOperation 记录升级来源）
     */
    public PersistentVolume upgrade(LegacyVolumeRecord record) {
        if (record == null) {
            throw new ProvisionerException(ProvisionerErrorCode.LEGACY_MISSING_FIELD,
                    "legacy record is null");
        }
        int version = record.getFormatVersion();
        if (version < 1) {
            throw new ProvisionerException(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION,
                    "unrecognized record version: " + version);
        }
        if (version > SUPPORTED_VERSION) {
            throw new ProvisionerException(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION,
                    "record version " + version + " is newer than supported "
                            + SUPPORTED_VERSION + "; refusing to downgrade-interpret");
        }
        Map<String, String> f = record.getFields();
        String name = requireField(f, "name", version);

        PersistentVolume volume;
        if (version == 1) {
            volume = upgradeV1(name, f);
        } else {
            volume = upgradeV2(name, f);
        }
        volume.setLastOperation("upgraded-from-v" + version);
        return volume;
    }

    private PersistentVolume upgradeV1(String name, Map<String, String> f) {
        String sizeText = requireField(f, "size", 1);
        long bytes = parseKubernetesSize(sizeText);
        AccessMode mode = parseV1Mode(requireField(f, "mode", 1));
        String storageClass = f.getOrDefault("storageClass", "default");
        ReclaimPolicy reclaim = f.containsKey("reclaim")
                ? parseReclaim(f.get("reclaim"), "reclaim")
                : ReclaimPolicy.RETAIN;
        return new PersistentVolume(name, bytes, EnumSet.of(mode), storageClass, reclaim);
    }

    private PersistentVolume upgradeV2(String name, Map<String, String> f) {
        String sizeMbText = requireField(f, "sizeMb", 2);
        long sizeMb = parseNonNegativeLong(sizeMbText, "sizeMb");
        long bytes = Math.multiplyExact(sizeMb, 1024L * 1024L);

        String modesText = requireField(f, "modes", 2);
        Set<AccessMode> modes = parseV2Modes(modesText);

        String storageClass = requireField(f, "storageClass", 2);
        if (storageClass.isBlank()) {
            throw invalid("storageClass", "blank storageClass");
        }
        ReclaimPolicy reclaim = parseReclaim(requireField(f, "reclaim", 2), "reclaim");
        return new PersistentVolume(name, bytes, modes, storageClass, reclaim);
    }

    private static String requireField(Map<String, String> f, String key, int version) {
        String value = f.get(key);
        if (value == null) {
            throw new ProvisionerException(ProvisionerErrorCode.LEGACY_MISSING_FIELD,
                    "v" + version + " record missing required field '" + key + "'");
        }
        return value;
    }

    private static ProvisionerException invalid(String field, String detail) {
        return new ProvisionerException(ProvisionerErrorCode.LEGACY_INVALID_FIELD,
                "invalid field '" + field + "': " + detail);
    }

    /**
     * 解析 K8s 风格计数字节串：支持 Ki/Mi/Gi/Ti（二进制 1024 进制），以及纯字节数。
     * 为保证确定性，不接受小数、不支持未知单位、不接受前导空白以外的格式歧义。
     */
    static long parseKubernetesSize(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            throw invalid("size", "empty size");
        }
        // 纯数字 = 字节
        if (Character.isDigit(t.charAt(t.length() - 1))) {
            return parseNonNegativeLong(t, "size");
        }
        String suffix = t.substring(t.length() - 2);
        String numberPart = t.substring(0, t.length() - 2).trim();
        long multiplier = switch (suffix) {
            case "Ki" -> 1024L;
            case "Mi" -> 1024L * 1024L;
            case "Gi" -> 1024L * 1024L * 1024L;
            case "Ti" -> 1024L * 1024L * 1024L * 1024L;
            default -> throw invalid("size", "unknown binary suffix: " + suffix);
        };
        long number = parseNonNegativeLong(numberPart, "size");
        try {
            return Math.multiplyExact(number, multiplier);
        } catch (ArithmeticException e) {
            throw invalid("size", "value overflows: " + text);
        }
    }

    private static long parseNonNegativeLong(String text, String field) {
        long value;
        try {
            value = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw invalid(field, "not an integer: '" + text + "'");
        }
        if (value < 0) {
            throw invalid(field, "must be non-negative: " + value);
        }
        return value;
    }

    private static AccessMode parseV1Mode(String text) {
        return switch (text.trim()) {
            case "RO" -> AccessMode.ROX;
            case "RW" -> AccessMode.RWO;
            case "RWM" -> AccessMode.RWX;
            default -> throw invalid("mode", "unknown v1 mode: '" + text + "'");
        };
    }

    private static Set<AccessMode> parseV2Modes(String text) {
        EnumSet<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        String[] tokens = text.split(",");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            AccessMode mode = switch (t) {
                case "ReadOnlyMany" -> AccessMode.ROX;
                case "ReadWriteOnce" -> AccessMode.RWO;
                case "ReadWriteMany" -> AccessMode.RWX;
                default -> throw invalid("modes", "unknown v2 access mode: '" + t + "'");
            };
            modes.add(mode);
        }
        if (modes.isEmpty()) {
            throw invalid("modes", "at least one access mode required: '" + text + "'");
        }
        return modes;
    }

    private static ReclaimPolicy parseReclaim(String text, String field) {
        return switch (text.trim()) {
            case "Delete", "DELETE" -> ReclaimPolicy.DELETE;
            case "Retain", "RETAIN" -> ReclaimPolicy.RETAIN;
            case "Recycle", "RECYCLE" -> ReclaimPolicy.RECYCLE;
            default -> throw invalid(field, "unknown reclaim policy: '" + text + "'");
        };
    }
}
