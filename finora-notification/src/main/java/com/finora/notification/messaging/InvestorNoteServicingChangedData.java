package com.finora.notification.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record InvestorNoteServicingChangedData(String changeType, Long loanApplicationId,
        String loanNumber, int daysPastDue, int debtGroup, String amount,
        LocalDate maturityDate, boolean externalPushRequired, Instant changedAt,
        List<Recipient> recipients) {
    public record Recipient(Long noteId, String noteNumber, String investorId) {}
}
