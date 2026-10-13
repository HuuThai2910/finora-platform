package com.finora.notification.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record InvestorNoteServicingChangedData(String changeType, Long loanApplicationId,
        String loanNumber, int daysPastDue, int debtGroup, String amount,
        LocalDate maturityDate, boolean externalPushRequired, Instant changedAt,
        List<Recipient> recipients) {
    /** {@code amount} là phần tiền của riêng Note này; event cũ không có trường này thì null. */
    public record Recipient(Long noteId, String noteNumber, String investorId, String amount) {}
}
