package com.github.xingjianluo.storagevolumeprovisionerjava.web;

import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerErrorCode;
import com.github.xingjianluo.storagevolumeprovisionerjava.error.ProvisionerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把领域异常映射为带稳定错误代码的 HTTP 响应。
 * 错误代码与 HTTP 状态的映射集中在此，调用方按 {@code code} 字段区分原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 各错误代码对应的 HTTP 状态。 */
    private static final Map<ProvisionerErrorCode, HttpStatus> STATUS_MAP = Map.ofEntries(
            Map.entry(ProvisionerErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST),
            Map.entry(ProvisionerErrorCode.LEGACY_MISSING_FIELD, HttpStatus.BAD_REQUEST),
            Map.entry(ProvisionerErrorCode.LEGACY_INVALID_FIELD, HttpStatus.BAD_REQUEST),
            Map.entry(ProvisionerErrorCode.REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ProvisionerErrorCode.VOLUME_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ProvisionerErrorCode.SNAPSHOT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ProvisionerErrorCode.VOLUME_ALREADY_BOUND, HttpStatus.CONFLICT),
            Map.entry(ProvisionerErrorCode.ILLEGAL_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ProvisionerErrorCode.UNSUPPORTED_RECORD_VERSION, HttpStatus.UPGRADE_REQUIRED),
            Map.entry(ProvisionerErrorCode.CAPACITY_NOT_MATCHED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ProvisionerErrorCode.ACCESS_MODE_NOT_MATCHED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ProvisionerErrorCode.STORAGE_CLASS_NOT_MATCHED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ProvisionerErrorCode.UNKNOWN_RECLAIM_POLICY, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ProvisionerErrorCode.STORAGE_BACKEND_FAILURE, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(ProvisionerException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleDomain(ProvisionerException e) {
        HttpStatus status = STATUS_MAP.getOrDefault(e.getCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        log.warn("http-error code={} status={} message={}", e.getCode(), status, e.getMessage());
        return ResponseEntity.status(status)
                .body(new Dtos.ErrorResponse(e.getCode().name(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("http-error code=INVALID_REQUEST status=400 message={}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse(
                        ProvisionerErrorCode.INVALID_REQUEST.name(), e.getMessage()));
    }
}
