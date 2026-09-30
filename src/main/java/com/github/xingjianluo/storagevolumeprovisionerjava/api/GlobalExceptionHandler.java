package com.github.xingjianluo.storagevolumeprovisionerjava.api;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisioningException;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把业务原因码统一映射为稳定的 HTTP 响应，保证各入口返回一致、可解释。
 * 响应体始终包含 success/action/reason/message，便于调用方按原因分支。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(ProvisioningException ex) {
        ReasonCode reason = ex.getReason();
        HttpStatus status = mapStatus(reason);
        log.warn("业务异常: reason={}, status={}, message={}", reason, status, ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "action", "ERROR",
                "reason", reason.name(),
                "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "action", "ERROR",
                "reason", "INTERNAL_ERROR",
                "message", String.valueOf(ex.getMessage())));
    }

    private HttpStatus mapStatus(ReasonCode reason) {
        return switch (reason) {
            case VOLUME_NOT_FOUND, CLAIM_NOT_FOUND, SNAPSHOT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS, CLAIM_ALREADY_BOUND, VOLUME_ALREADY_BOUND -> HttpStatus.CONFLICT;
            case ILLEGAL_STATE_TRANSITION, VOLUME_IN_USE, VOLUME_NOT_BOUND, CLAIM_NOT_BOUND,
                 RECLAIM_POLICY_CHANGE_REJECTED, SNAPSHOT_VOLUME_MISMATCH -> HttpStatus.CONFLICT;
            case UNSUPPORTED_RECORD_VERSION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LEGACY_FIELD_MISSING, LEGACY_FIELD_INVALID, INVALID_ARGUMENT,
                 SNAPSHOT_RESTORE_CAPACITY_MISMATCH -> HttpStatus.BAD_REQUEST;
            case NO_VOLUME_AVAILABLE, CAPACITY_NOT_SATISFIED, ACCESS_MODE_NOT_SATISFIED,
                 STORAGE_CLASS_NOT_MATCHED -> HttpStatus.CONFLICT;
            case RECLAIM_FAILED, SNAPSHOT_RESTORE_FAILED -> HttpStatus.CONFLICT;
        };
    }
}
