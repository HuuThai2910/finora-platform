package com.finora.loan.integration.fineract;

public class FineractIntegrationException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public FineractIntegrationException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
