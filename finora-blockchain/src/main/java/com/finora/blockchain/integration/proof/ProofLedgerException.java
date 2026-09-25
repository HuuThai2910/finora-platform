package com.finora.blockchain.integration.proof;

import lombok.Getter;

@Getter
public class ProofLedgerException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public ProofLedgerException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
