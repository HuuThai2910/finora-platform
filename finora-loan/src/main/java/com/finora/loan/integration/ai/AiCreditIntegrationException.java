package com.finora.loan.integration.ai;

import lombok.Getter;

@Getter
public class AiCreditIntegrationException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public AiCreditIntegrationException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
