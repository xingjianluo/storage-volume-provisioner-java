package com.github.xingjianluo.storagevolumeprovisionerjava.error;

/**
 * 业务异常：携带可区分的 {@link ReasonCode}，所有入口语义一致。
 */
public class ProvisioningException extends RuntimeException {

    private final ReasonCode reason;

    public ProvisioningException(ReasonCode reason) {
        super(reason.getDefaultMessage());
        this.reason = reason;
    }

    public ProvisioningException(ReasonCode reason, String detail) {
        super(reason.getDefaultMessage() + "：" + detail);
        this.reason = reason;
    }

    public ReasonCode getReason() {
        return reason;
    }
}
