package com.finora.blockchain.dto.response;

import com.finora.blockchain.domain.proof.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.domain.proof.ProofType;
import java.time.Instant;
import java.util.UUID;

/**
 * Một proof trong explorer. Không có payload gốc: chỉ hash, scheme để tính lại hash và receipt.
 *
 * @param receipt null khi chưa xác nhận; {@code kind} phân biệt rõ {@code FABRIC_ON_CHAIN} với {@code MOCK_NOT_ON_CHAIN}
 * @param chainCheck kết quả đối chiếu DB ↔ Fabric gần nhất; null nếu chưa đối chiếu hoặc proof không nằm trên Fabric
 */
public record ProofResponse(
        UUID proofId,
        ProofType proofType,
        String aggregateType,
        String aggregateId,
        Long loanApplicationId,
        Source source,
        Hash hash,
        ProofProviderType provider,
        ProofSubmissionStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastErrorCode,
        String lastErrorDetail,
        Receipt receipt,
        ChainCheck chainCheck,
        Instant createdAt,
        Instant updatedAt
) {

    /** Event nguồn: người kiểm toán lấy {@code data} của event này ở service nguồn để tính lại hash. */
    public record Source(String service, UUID eventId, String eventType, Integer eventVersion, Instant occurredAt) {
    }

    public record Hash(String value, int version, String scheme, String description) {
    }

    public record Receipt(
            String kind,
            boolean onChain,
            String transactionId,
            String blockReference,
            Instant confirmedAt,
            String notice
    ) {
    }

    public record ChainCheck(ProofChainCheckStatus status, Instant checkedAt) {
    }
}
