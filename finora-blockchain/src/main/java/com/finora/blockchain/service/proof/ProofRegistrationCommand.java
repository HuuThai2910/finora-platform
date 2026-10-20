package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.ProofType;
import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu đầu vào nội bộ; chỉ nhận hash và định danh, không nhận tài liệu/PII gốc.
 *
 * @param sourceEventType loại event nguồn (ví dụ {@code LoanContractActivated}) để biết artifact nào tính lại hash
 * @param loanApplicationId khoản vay liên quan, cho explorer liệt kê proof theo khoản vay
 * @param payloadVersion version của {@link com.finora.blockchain.domain.proof.ProofHashScheme}
 */
public record ProofRegistrationCommand(
        String sourceService,
        UUID sourceEventId,
        String sourceEventType,
        Integer sourceEventVersion,
        Instant sourceOccurredAt,
        ProofType proofType,
        String aggregateType,
        String aggregateId,
        Long loanApplicationId,
        String payloadHash,
        int payloadVersion
) {
}
