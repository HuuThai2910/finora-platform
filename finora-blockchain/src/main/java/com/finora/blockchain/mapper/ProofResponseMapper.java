package com.finora.blockchain.mapper;

import com.finora.blockchain.domain.proof.ProofHashScheme;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.dto.response.ProofResponse;

/** Chuyển entity sang response explorer; receipt MOCK luôn kèm cảnh báo không nằm trên Fabric (LEGAL-DATA-01). */
public final class ProofResponseMapper {

    public static final String MOCK_NOTICE =
            "Receipt MOCK chỉ dùng cho demo; bằng chứng này KHÔNG được ghi lên Hyperledger Fabric";

    private ProofResponseMapper() {
    }

    public static ProofResponse toResponse(ProofSubmission proof) {
        ProofHashScheme scheme = proof.hashScheme();
        return new ProofResponse(
                proof.getProofId(),
                proof.getProofType(),
                proof.getAggregateType(),
                proof.getAggregateId(),
                proof.getLoanApplicationId(),
                new ProofResponse.Source(proof.getSourceService(), proof.getSourceEventId(), proof.getSourceEventType(),
                        proof.getSourceEventVersion(), proof.getSourceOccurredAt()),
                new ProofResponse.Hash(proof.getPayloadHash(), proof.getPayloadVersion(), scheme.name(),
                        scheme.description()),
                proof.getProvider(),
                proof.getStatus(),
                proof.getAttemptCount(),
                proof.getStatus() == ProofSubmissionStatus.PENDING ? proof.getAvailableAt() : null,
                proof.getLastErrorCode(),
                proof.getLastErrorDetail(),
                receipt(proof),
                proof.getChainCheckStatus() == null ? null
                        : new ProofResponse.ChainCheck(proof.getChainCheckStatus(), proof.getChainCheckedAt()),
                proof.getCreatedAt(),
                proof.getUpdatedAt());
    }

    private static ProofResponse.Receipt receipt(ProofSubmission proof) {
        if (proof.getStatus() != ProofSubmissionStatus.CONFIRMED) {
            return null;
        }
        boolean fabric = proof.getProvider() == ProofProviderType.HYPERLEDGER_FABRIC;
        return new ProofResponse.Receipt(
                fabric ? "FABRIC_ON_CHAIN" : "MOCK_NOT_ON_CHAIN",
                proof.isOnChain(),
                proof.getProviderTransactionId(),
                proof.getProviderBlockReference(),
                proof.getConfirmedAt(),
                fabric ? null : MOCK_NOTICE);
    }
}
