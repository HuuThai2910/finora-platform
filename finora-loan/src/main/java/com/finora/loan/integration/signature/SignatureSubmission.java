package com.finora.loan.integration.signature;

import java.util.Objects;

/** Kết quả tối thiểu từ provider; raw signature chỉ tồn tại trong memory để tạo evidence hash. */
public record SignatureSubmission(
        SignatureSubmissionStatus status,
        String providerTransactionId,
        String documentId,
        String signatureValue,
        String timestampSignature,
        String providerMessage
) {
    public SignatureSubmission {
        Objects.requireNonNull(status, "status");
        requireText(providerTransactionId, "providerTransactionId");
        requireText(documentId, "documentId");
        if (status == SignatureSubmissionStatus.COMPLETED
                && (signatureValue == null || signatureValue.isBlank())) {
            throw new IllegalArgumentException("Kết quả COMPLETED phải có signatureValue");
        }
    }

    public static SignatureSubmission pending(String transactionId, String documentId, String message) {
        return new SignatureSubmission(
                SignatureSubmissionStatus.PENDING, transactionId, documentId, null, null, message);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
    }
}
