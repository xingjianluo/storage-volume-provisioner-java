package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

/** 向卷写入数据的请求体（base64）。 */
public record DataRequest(long offset, String base64Data) {
}
