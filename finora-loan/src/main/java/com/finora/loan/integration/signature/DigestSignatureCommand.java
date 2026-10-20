package com.finora.loan.integration.signature;

import com.finora.loan.domain.contract.SignatureMethod;
import java.util.Objects;

/**
 * Yêu cầu ký một digest SHA-256 (PAdES): digest là hash của DER signed attributes, không phải hash cả tệp PDF.
 * Provider không nhận PDF hay dữ liệu hồ sơ vay qua boundary này.
 */
public record DigestSignatureCommand(
        String contractNumber,
        String signerId,
        String digestHex,
        String providerTransactionId,
        String documentId,
        String description,
        SignatureMethod requestedMethod
) {
    public DigestSignatureCommand {
        requireText(contractNumber, "contractNumber");
        requireText(signerId, "signerId");
        requireText(digestHex, "digestHex");
        if (!digestHex.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("digestHex phải là SHA-256 chữ thường");
        }
        requireText(providerTransactionId, "providerTransactionId");
        requireText(documentId, "documentId");
        requireText(description, "description");
        Objects.requireNonNull(requestedMethod, "requestedMethod");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
    }
}
