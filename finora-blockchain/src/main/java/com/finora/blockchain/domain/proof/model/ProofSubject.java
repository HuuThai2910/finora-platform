package com.finora.blockchain.domain.proof.model;

import com.finora.blockchain.domain.proof.enums.ProofType;

/**
 * Mốc nghiệp vụ được neo: loại proof + aggregate nghiệp vụ (ví dụ {@code LoanContract/LC-2026-000123}) và khoản vay
 * liên quan để explorer liệt kê mọi proof của một khoản vay. Chỉ là định danh logic, không phải FK chéo service.
 */
public record ProofSubject(
        ProofType proofType,
        String aggregateType,
        String aggregateId,
        Long loanApplicationId
) {
}
