package com.finora.payment.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code RepaymentDistributed.v1}.
 *
 * @param borrowerId ID logic của người vay đã lưu trên khoản trả (claim {@code user_id}, không phải PII); field optional
 *                   thêm sau (P4, W3F) để Notification báo người vay không cần bảng tra, consumer cũ bỏ qua
 */
public record RepaymentDistributedEventData(UUID repaymentId, String repaymentReference,
        String repaymentType, String quoteId, String platformFee,
        String investorFeeShare, String finoraFeeShare,
        Long loanApplicationId, Long fineractLoanId, Long fineractTransactionId,
        String amount, String principalAmount, String interestAmount, String feeAmount,
        String penaltyAmount, String outstandingPrincipal, String outstandingInterest,
        String outstandingFee, String outstandingPenalty, String totalOutstanding,
        String overdueAmount, String currency,
        LocalDate transactionDate, LocalDate nextDueDate, String nextDueAmount,
        Instant completedAt, List<NoteAllocation> allocations, String borrowerId) {
    public record NoteAllocation(Long noteId, String noteNumber, String investorId,
            String principalAmount, String interestAmount, String prepaymentFeeAmount) {}
}
