package com.github.xingjianluo.storagevolumeprovisionerjava.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.AccessMode;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.PersistentVolume;
import com.github.xingjianluo.storagevolumeprovisionerjava.model.ReclaimPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

/**
 * 旧格式卷记录迁移器。
 *
 * <p>支持的旧记录（formatVersion=1）示例：</p>
 * <pre>{@code
 * { "formatVersion": 1,
 *   "id": "vol-old-1",          // 必填，卷名
 *   "sizeGb": 10,               // 必填，容量（整数 GB，>0）
 *   "mode": "RW",               // 可选，默认 RW（见映射表）
 *   "reclaim": "delete",        // 可选，默认 retain
 *   "diskClass": "ssd" }        // 可选，缺失视为不指定存储类
 * }</pre>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>仅支持 formatVersion=1；缺失版本字段视为格式错误并拒绝；
 *       版本高于 1 一律 {@code UNSUPPORTED_RECORD_VERSION}，绝不按旧记录处理；</li>
 *   <li>必填字段缺失 -> {@code LEGACY_FIELD_MISSING}；值非法 -> {@code LEGACY_FIELD_INVALID}，
 *       两类原因可区分；</li>
 *   <li>升级是纯函数：相同输入永远得到相同输出（GB->Byte 用 1024^3，枚举规整，顺序无关）。</li>
 * </ul>
 */
@Component
public class LegacyVolumeMigrator {

    private static final Logger log = LoggerFactory.getLogger(LegacyVolumeMigrator.class);

    /** 当前支持升级的唯一旧格式版本。 */
    public static final int SUPPORTED_LEGACY_VERSION = 1;
    /** 当前内部记录格式版本。 */
    public static final int CURRENT_FORMAT_VERSION = 2;

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private static final Map<String, EnumSet<AccessMode>> MODE_MAPPING = Map.of(
            "RO", EnumSet.of(AccessMode.RWO, AccessMode.ROX),
            "RW", EnumSet.of(AccessMode.RWO, AccessMode.RWX));

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PersistentVolume migrate(String recordJson) {
        if (recordJson == null || recordJson.isBlank()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_MISSING, "记录内容为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(recordJson);
        } catch (Exception ex) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID, "记录不是合法 JSON：" + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID, "记录顶层必须是 JSON 对象");
        }

        JsonNode versionNode = root.get("formatVersion");
        if (versionNode == null || versionNode.isNull()) {
            // 没有版本号：可能是更古老的未知格式，不能当作 v1 静默处理
            throw new ProvisioningException(ReasonCode.UNSUPPORTED_RECORD_VERSION,
                    "记录缺少 formatVersion 字段，无法判定格式");
        }
        if (!versionNode.canConvertToInt() || !versionNode.isIntegralNumber()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "formatVersion 必须是整数，实际=" + versionNode);
        }
        int version = versionNode.asInt();
        if (version > SUPPORTED_LEGACY_VERSION) {
            throw new ProvisioningException(ReasonCode.UNSUPPORTED_RECORD_VERSION,
                    "记录格式版本=" + version + " 高于支持版本=" + SUPPORTED_LEGACY_VERSION
                            + "（当前内部版本=" + CURRENT_FORMAT_VERSION + "），拒绝按旧记录处理");
        }
        if (version < SUPPORTED_LEGACY_VERSION) {
            throw new ProvisioningException(ReasonCode.UNSUPPORTED_RECORD_VERSION,
                    "记录格式版本=" + version + " 低于最低支持版本=" + SUPPORTED_LEGACY_VERSION);
        }

        String name = requireText(root, "id").trim();
        long sizeGb = requireSizeGb(root);
        EnumSet<AccessMode> modes = parseMode(root);
        ReclaimPolicy reclaim = parseReclaim(root);
        String storageClass = parseStorageClass(root);
        long capacityBytes = sizeGb * BYTES_PER_GB;

        PersistentVolume volume = new PersistentVolume(name, capacityBytes, modes, storageClass, reclaim);
        log.info("旧记录升级: formatVersion={}->{}, id={}, sizeGb={}->capacityBytes={}, mode={}->{}, reclaim={}, diskClass={}",
                version, CURRENT_FORMAT_VERSION, name, sizeGb, capacityBytes,
                root.path("mode").asText("(缺省RW)"), modes, reclaim, storageClass);
        return volume;
    }

    private String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_MISSING, "缺少必填字段：" + field);
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 " + field + " 必须是非空字符串，实际=" + node);
        }
        return node.asText();
    }

    private long requireSizeGb(JsonNode root) {
        JsonNode node = root.get("sizeGb");
        if (node == null || node.isNull()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_MISSING, "缺少必填字段：sizeGb");
        }
        if (!node.isIntegralNumber()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 sizeGb 必须是整数 GB，实际=" + node);
        }
        long sizeGb = node.asLong();
        if (sizeGb <= 0) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 sizeGb 必须为正整数，实际=" + sizeGb);
        }
        if (sizeGb > Integer.MAX_VALUE) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 sizeGb 过大：" + sizeGb);
        }
        return sizeGb;
    }

    private EnumSet<AccessMode> parseMode(JsonNode root) {
        JsonNode node = root.get("mode");
        if (node == null || node.isNull()) {
            return EnumSet.copyOf(MODE_MAPPING.get("RW"));
        }
        if (!node.isTextual()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 mode 必须是字符串 RO/RW，实际=" + node);
        }
        String key = node.asText().trim().toUpperCase(Locale.ROOT);
        EnumSet<AccessMode> modes = MODE_MAPPING.get(key);
        if (modes == null) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 mode 仅支持 RO/RW，实际=" + node.asText());
        }
        return EnumSet.copyOf(modes);
    }

    private ReclaimPolicy parseReclaim(JsonNode root) {
        JsonNode node = root.get("reclaim");
        if (node == null || node.isNull()) {
            // 旧系统缺省最保守语义：保留
            return ReclaimPolicy.RETAIN;
        }
        if (!node.isTextual()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 reclaim 必须是字符串 delete/retain/recycle，实际=" + node);
        }
        try {
            return ReclaimPolicy.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 reclaim 仅支持 delete/retain/recycle，实际=" + node.asText());
        }
    }

    private String parseStorageClass(JsonNode root) {
        JsonNode node = root.get("diskClass");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ProvisioningException(ReasonCode.LEGACY_FIELD_INVALID,
                    "字段 diskClass 必须是字符串，实际=" + node);
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
