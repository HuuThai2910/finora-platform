package com.finora.blockchain.dto.response;

import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Kết quả kiểm một proof.
 *
 * @param artifact so hash tính lại từ artifact người gọi gửi (data của event nguồn) với hash đã lưu; null nếu không gửi
 * @param chain so bản ghi PostgreSQL với bản ghi trên Fabric
 */
public record ProofVerificationResponse(
        UUID proofId,
        ProofProviderType provider,
        ProofSubmissionStatus status,
        ArtifactCheck artifact,
        ChainVerification chain,
        Instant verifiedAt
) {

    /** {@code result}: {@code MATCHED} hoặc {@code MISMATCHED}. */
    public record ArtifactCheck(String result, String scheme, String expectedHash, String recomputedHash) {
    }

    /**
     * {@code result}: {@code MATCHED}, {@code MISMATCHED}, {@code MISSING_ON_CHAIN} (đã lưu làm kết quả đối chiếu),
     * {@code NOT_ON_CHAIN_MOCK}, {@code NOT_CONFIRMED}, {@code LEDGER_UNAVAILABLE} (không lưu).
     */
    public record ChainVerification(
            String result,
            String detail,
            String transactionId,
            String payloadHash,
            String recordedAt,
            String submitterMspId
    ) {
    }
}
