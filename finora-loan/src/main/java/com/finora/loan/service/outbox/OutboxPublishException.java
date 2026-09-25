package com.finora.loan.service.outbox;

/** Phân loại lỗi transport để relay retry có giới hạn hoặc đưa thẳng vào dead-letter. */
public class OutboxPublishException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public OutboxPublishException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
