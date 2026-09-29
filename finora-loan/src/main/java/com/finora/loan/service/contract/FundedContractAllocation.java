package com.finora.loan.service.contract;

import java.math.BigDecimal;

/** Snapshot lender allocation do Investment khóa và Loan đối chiếu trước khi lập Contract. */
public record FundedContractAllocation(
        Long commitmentId,
        String investorId,
        BigDecimal amount,
        BigDecimal sharePercent,
        String paymentHoldReference
) {
}
