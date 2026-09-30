package com.github.xingjianluo.storagevolumeprovisionerjava.error;

/**
 * 供给器领域异常：携带稳定的 {@link ProvisionerErrorCode} 与可解释明细。
 */
public class ProvisionerException extends RuntimeException {

    private final ProvisionerErrorCode code;

    public ProvisionerException(ProvisionerErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ProvisionerException(ProvisionerErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ProvisionerErrorCode getCode() {
        return code;
    }
}
