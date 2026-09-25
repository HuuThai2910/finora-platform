package com.finora.loan.integration.signature;

/** Boundary thay thế được giữa mock phát triển và chữ ký số production. */
public interface SignatureProvider {

    SignatureSubmission submit(SignatureCommand command);

    SignatureSubmission status(String providerTransactionId, String documentId);

    com.finora.loan.domain.contract.SignatureProviderType type();

    com.finora.loan.domain.contract.SignatureMethod method();
}
