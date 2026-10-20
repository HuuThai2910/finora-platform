package com.finora.blockchain.integration.proof;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Bản ghi proof đọc từ chaincode finora-ledger. {@code transactionId} là giao dịch Fabric đã ghi bản ghi lần đầu;
 * {@code recordedAt} là timestamp của proposal, mọi endorser dùng chung nên không lệch giữa hai tổ chức.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerProofRecord(
        UUID proofId,
        String proofType,
        String aggregateType,
        String aggregateId,
        String payloadHash,
        int payloadVersion,
        String sourceService,
        String txId,
        String recordedAt,
        String submitterMspId
) {

    /** Bản ghi trên ledger có đúng nội dung FINORA đã gửi không (không so txId/thời điểm). */
    public boolean matches(ProofLedgerCommand command) {
        return command.proofId().equals(proofId)
                && command.proofType().name().equals(proofType)
                && command.aggregateType().equals(aggregateType)
                && command.aggregateId().equals(aggregateId)
                && command.payloadHash().equals(payloadHash)
                && command.payloadVersion() == payloadVersion
                && command.sourceService().equals(sourceService);
    }
}
