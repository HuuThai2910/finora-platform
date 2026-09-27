package com.finora.investment.service.outbox;

import lombok.Getter;

@Getter
public class InvestmentOutboxPublishException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public InvestmentOutboxPublishException(
            String errorCode,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
