package com.finora.loan.integration.signature;

import lombok.Getter;

@Getter
public class SmartCaIntegrationException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public SmartCaIntegrationException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
