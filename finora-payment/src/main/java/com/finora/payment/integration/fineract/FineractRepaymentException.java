package com.finora.payment.integration.fineract;

public class FineractRepaymentException extends RuntimeException {
    private final String code;
    private final boolean outcomeUnknown;

    public FineractRepaymentException(String code, String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.outcomeUnknown = outcomeUnknown;
    }
    public String code() { return code; }
    public boolean outcomeUnknown() { return outcomeUnknown; }
}
