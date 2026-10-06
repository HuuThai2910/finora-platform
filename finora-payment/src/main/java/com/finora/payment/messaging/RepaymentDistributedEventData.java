package com.finora.payment.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RepaymentDistributedEventData(UUID repaymentId, String repaymentReference,
        String repaymentType, String quoteId, String platformFee,
        Long loanApplicationId, Long fineractLoanId, Long fineractTransactionId,
        String amount, String principalAmount, String interestAmount, String feeAmount,
        String penaltyAmount, String outstandingPrincipal, String outstandingInterest,
        String outstandingFee, String outstandingPenalty, String totalOutstanding,
        String overdueAmount, String currency,
        LocalDate transactionDate, LocalDate nextDueDate, String nextDueAmount,
        Instant completedAt, List<NoteAllocation> allocations) {
    public record NoteAllocation(Long noteId, String noteNumber, String investorId,
            String principalAmount, String interestAmount) {}
}
