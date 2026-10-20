package com.finora.notification.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Payload servicing (trả nợ, quá hạn, tất toán, cơ cấu) mà Notification đọc. */
public final class LoanServicingEvents {

    private LoanServicingEvents() {
    }

    /**
     * {@code RepaymentDistributed.v1} (Payment): Fineract đã ghi và ledger đã chia. {@code nextDueDate/nextDueAmount}
     * là kỳ tiếp theo theo Fineract (nguồn nhắc lịch). {@code borrowerId} optional, chờ producer (P4).
     */
    public record RepaymentDistributed(UUID repaymentId, String repaymentType, Long loanApplicationId, String amount,
            String totalOutstanding, String currency, LocalDate transactionDate, LocalDate nextDueDate,
            String nextDueAmount, Instant completedAt, String borrowerId) {}

    /** {@code LoanDelinquencyChanged.v1} (Loan): phát mỗi lần DPD đổi, tức hằng ngày khi đang quá hạn. */
    public record DelinquencyChanged(Long loanApplicationId, String loanNumber, String borrowerId,
            int previousDaysPastDue, int daysPastDue, int previousDebtGroup, int debtGroup, String overdueAmount,
            LocalDate overdueSince, String totalOutstanding, Instant dataAsOf) {}

    /** {@code LoanSettled.v1} (Loan). */
    public record Settled(Long loanApplicationId, String loanNumber, String contractNumber, String borrowerId,
            Instant settledAt) {}

    /** {@code LoanRescheduleConsentRequested.v1} (Loan): FINORA thẩm định đạt. {@code borrowerId} optional (P2). */
    public record RescheduleConsentRequested(UUID requestId, Long loanApplicationId, String loanNumber,
            String requestType, Instant requestedAt, String borrowerId) {}

    /** {@code LoanRescheduleConsentCompleted.v1} (Investment): kết quả biểu quyết cuối cùng. */
    public record RescheduleConsentCompleted(UUID requestId, Long loanApplicationId, boolean approved,
            int requiredInvestors, int approvedInvestors, Instant resolvedAt) {}

    /**
     * {@code LoanRescheduled.v1} (Loan): Fineract đã chấp nhận lịch mới. {@code nextDueDate/nextDueAmount} optional,
     * chờ producer (P3); thiếu thì Notification chỉ dời ngày khi biết chắc và bỏ số tiền cũ.
     */
    public record Rescheduled(Long loanApplicationId, String loanNumber, String borrowerId, UUID requestId,
            String requestType, LocalDate previousMaturityDate, LocalDate newMaturityDate,
            LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms, Instant occurredAt,
            LocalDate nextDueDate, String nextDueAmount) {}
}
