package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

/**
 * 导入旧格式卷记录的请求体。
 *
 * <p>直接透传未解析的旧记录（formatVersion 字段由迁移器判定），
 * 典型旧记录形如：{"formatVersion":1,"id":"vol-x","sizeGb":10,"mode":"RW",...}</p>
 */
public record LegacyRecordRequest(String recordJson) {
}
