package com.finora.payment.messaging;

import java.time.Instant;
import java.util.List;

public record NoteOwnershipChangedEventData(Long loanApplicationId, Long listingId, String currency,
        String reason, String reference, Instant changedAt, List<NoteOwner> notes) {
    public record NoteOwner(Long noteId, String noteNumber, String investorId, String outstandingPrincipal) {}
}
