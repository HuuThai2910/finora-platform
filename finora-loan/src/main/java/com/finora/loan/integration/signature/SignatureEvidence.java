package com.finora.loan.integration.signature;

import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import java.util.Objects;

/** Bằng chứng tối thiểu Loan lưu để đối chiếu provider; không chứa OTP, token hoặc private key. */
public record SignatureEvidence(
        SignatureProviderType provider,
        SignatureMethod method,
        String providerTransactionId,
        String evidenceHash
) {
    public SignatureEvidence {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(method, "method");
        if (providerTransactionId == null || providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId không được để trống");
        }
        if (evidenceHash == null || !evidenceHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("evidenceHash phải là SHA-256 chữ thường");
        }
    }
}
