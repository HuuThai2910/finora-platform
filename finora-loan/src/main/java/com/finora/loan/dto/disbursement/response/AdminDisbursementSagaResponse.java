package com.finora.loan.dto.disbursement.response;

import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga giải ngân cho màn sự cố của admin (R2). Không có thông tin cá nhân người vay; số hợp đồng là externalId để đối chiếu
 * Fineract.
 */
public record AdminDisbursementSagaResponse(
        UUID sagaId,
        Long loanApplicationId,
        String contractNumber,
        DisbursementSagaStatus status,
        BigDecimal amount,
        String currency,
        String paymentReference,
        Long fineractLoanId,
        int attemptCount,
        int attemptsSinceRepair,
        int repairCount,
        Instant nextRetryAt,
        String lastErrorCode,
        String lastErrorDetail,
        String lastRepairBy,
        Instant lastRepairAt,
        String lastRepairNote,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminDisbursementSagaResponse from(DisbursementSaga saga) {
        return new AdminDisbursementSagaResponse(saga.getSagaId(), saga.getLoanApplicationId(), saga.getContractNumber(),
                saga.getStatus(), saga.getAmount(), saga.getCurrency(), saga.getPaymentReference(), saga.getFineractLoanId(),
                saga.getAttemptCount(), saga.attemptsSinceRepair(), saga.getRepairCount(), saga.getNextRetryAt(),
                saga.getLastErrorCode(), saga.getLastErrorDetail(), saga.getLastRepairBy(), saga.getLastRepairAt(),
                saga.getLastRepairNote(), saga.getCreatedAt(), saga.getUpdatedAt());
    }
}
