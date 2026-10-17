package com.finora.loan.dto.contract.response;

import java.time.Instant;

/**
 * Bản mới nhất của chuỗi chữ ký PAdES (R5). {@code signatureCount} là số chữ ký đã nhúng; khi bằng số bên ký thì đây
 * cũng là bản ký đủ mà {@code /document} trả sau khi hoàn tất.
 */
public record LoanContractSignedPdfResponse(
        String signatureFormat,
        int signatureCount,
        String contentHash,
        Integer contentLength,
        Instant lastSignedAt,
        String downloadPath
) {
}
