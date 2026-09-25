package com.finora.loan.integration.signature;

import com.finora.loan.domain.contract.SignatureMethod;
import java.util.Objects;

/** Chỉ chứa ID tham chiếu và hash; provider không nhận toàn bộ hồ sơ vay hoặc PDF qua boundary này. */
public record SignatureCommand(
        String contractNumber,
        String borrowerId,
        String documentHash,
        String pdfDocumentHash,
        String idempotencyKey,
        String providerTransactionId,
        String documentId,
        SignatureMethod requestedMethod
) {
    public SignatureCommand {
        requireText(contractNumber, "contractNumber");
        requireText(borrowerId, "borrowerId");
        requireHash(documentHash, "documentHash");
        requireHash(pdfDocumentHash, "pdfDocumentHash");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(providerTransactionId, "providerTransactionId");
        requireText(documentId, "documentId");
        Objects.requireNonNull(requestedMethod, "requestedMethod");
    }

    private static void requireHash(String value, String field) {
        requireText(value, field);
        if (!value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " phải là SHA-256 chữ thường");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
    }
}
