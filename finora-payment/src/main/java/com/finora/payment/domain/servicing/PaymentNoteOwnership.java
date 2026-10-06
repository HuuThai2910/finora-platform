package com.finora.payment.domain.servicing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_note_ownership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentNoteOwnership {
    @Id @Column(name = "note_id") private Long noteId;
    @Column(name = "note_number", nullable = false, length = 50, unique = true) private String noteNumber;
    @Column(name = "loan_application_id", nullable = false) private Long loanApplicationId;
    @Column(name = "listing_id", nullable = false) private Long listingId;
    @Column(name = "investor_id", nullable = false, length = 100) private String investorId;
    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2) private BigDecimal outstandingPrincipal;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "ownership_changed_at", nullable = false) private Instant ownershipChangedAt;
    @Column(name = "source_reference", nullable = false, length = 100) private String sourceReference;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static PaymentNoteOwnership create(Long noteId, String noteNumber, Long loanApplicationId,
            Long listingId, String investorId, BigDecimal outstandingPrincipal, String currency,
            Instant changedAt, String sourceReference, Instant now) {
        PaymentNoteOwnership value = new PaymentNoteOwnership();
        value.noteId = noteId;
        value.noteNumber = noteNumber;
        value.loanApplicationId = loanApplicationId;
        value.listingId = listingId;
        value.apply(investorId, outstandingPrincipal, currency, changedAt, sourceReference, now);
        value.createdAt = now;
        return value;
    }

    public void apply(String investorId, BigDecimal outstandingPrincipal, String currency,
            Instant changedAt, String sourceReference, Instant now) {
        if (ownershipChangedAt != null && changedAt.isBefore(ownershipChangedAt)) return;
        this.investorId = investorId;
        this.outstandingPrincipal = outstandingPrincipal.setScale(2);
        this.currency = currency;
        this.ownershipChangedAt = changedAt;
        this.sourceReference = sourceReference;
        this.updatedAt = now;
    }

    public void repayPrincipal(BigDecimal amount, Instant now) {
        BigDecimal next = outstandingPrincipal.subtract(amount);
        if (next.signum() < 0) throw new IllegalStateException("Phân bổ gốc vượt dư nợ Note");
        outstandingPrincipal = next.setScale(2);
        updatedAt = now;
    }
}
