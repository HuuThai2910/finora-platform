package com.finora.loan.domain.contract;

import java.time.Instant;

/**
 * Metadata một chữ ký đã nhúng (value object chỉ đọc), lấy bằng JPQL projection để màn chi tiết và trang biên nhận
 * không kéo bytes PDF của từng phiên bản.
 */
public record ContractPdfSignatureSummary(
        Long id,
        ContractPartyType signerType,
        String signerId,
        Integer sequenceNumber,
        SignatureProviderType provider,
        SignatureMethod method,
        String contentHash,
        Integer contentLength,
        String certificateSerial,
        String certificateSha256,
        String cmsHash,
        String evidenceHash,
        Instant signingTime,
        Instant signedAt
) {
}
