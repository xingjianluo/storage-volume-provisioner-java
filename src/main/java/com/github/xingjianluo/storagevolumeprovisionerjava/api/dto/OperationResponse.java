package com.github.xingjianluo.storagevolumeprovisionerjava.api.dto;

/**
 * 统一操作结果。
 *
 * @param success 是否成功
 * @param action  实际执行的动作/语义（如 BOUND / PENDING / RETAINED / RECYCLED / DELETED / UPGRADED）
 * @param message 可解释的说明（含判定依据）
 * @param data    可选负载
 */
public record OperationResponse(boolean success, String action, String message, Object data) {

    public static OperationResponse ok(String action, String message, Object data) {
        return new OperationResponse(true, action, message, data);
    }

    public static OperationResponse ok(String action, String message) {
        return new OperationResponse(true, action, message, null);
    }
}
