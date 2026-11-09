package com.finora.notification.messaging.event;

import java.time.Instant;
import java.time.LocalDate;

/** Payload hợp đồng và giải ngân của Loan mà Notification đọc. */
public final class LoanContractEvents {

    private LoanContractEvents() {
    }

    /**
     * {@code InvestorSignatureRequested.v1}: không mang investorId; người nhận lấy từ allocation đã lưu.
     * {@code applicationCode}/{@code contractCode} (mã hiển thị) optional, producer cũ không gửi.
     */
    public record InvestorSignatureRequested(Long loanApplicationId, String applicationNumber, String contractNumber,
            Long allocationVersion, Integer lenderCount, Instant expiresAt, String applicationCode,
            String contractCode) {}

    /** {@code BorrowerSignatureRequested.v1}. {@code borrowerId} optional, chờ producer (P1, B24/B64). */
    public record BorrowerSignatureRequested(Long loanApplicationId, String applicationNumber, String contractNumber,
            Integer lenderCount, Instant expiresAt, String borrowerId) {}

    /** {@code LoanContractActivated.v1}. {@code borrowerId} (P2) và {@code contractCode} (mã hiển thị) optional. */
    public record ContractActivated(Long loanApplicationId, String applicationNumber, String contractNumber,
            Long allocationVersion, Instant activatedAt, String borrowerId, String contractCode) {}

    /**
     * {@code LoanDisbursed.v1}. {@code loanNumber}/{@code maturityDate} có từ Wave 2D (B34);
     * {@code borrowerId} (P2), {@code nextDueDate}/{@code nextDueAmount} (P3), {@code contractCode} (mã hiển thị)
     * optional.
     */
    public record Disbursed(Long loanApplicationId, String applicationNumber, String contractNumber, String amount,
            String currency, Instant disbursedAt, String loanNumber, LocalDate maturityDate, String borrowerId,
            LocalDate nextDueDate, String nextDueAmount, String contractCode) {}
}
