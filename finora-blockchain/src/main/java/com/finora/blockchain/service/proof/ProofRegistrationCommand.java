package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;

/** Dữ liệu đầu vào nội bộ; chỉ nhận hash và định danh, không nhận tài liệu/PII gốc. */
public record ProofRegistrationCommand(
        String sourceService,
        UUID sourceEventId,
        String aggregateType,
        String aggregateId,
        ProofType proofType,
        String payloadHash,
        int payloadVersion
) {
}
