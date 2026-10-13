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
        // Dư nợ chỉ lấy từ event lúc tạo bản ghi; về sau Payment tự giảm qua repayPrincipal.
        value.outstandingPrincipal = outstandingPrincipal.setScale(2);
        value.apply(investorId, outstandingPrincipal, currency, changedAt, sourceReference, now);
        value.createdAt = now;
        return value;
    }

    /**
     * Đổi chủ Note theo {@code NoteOwnershipChanged}. Không ghi đè dư nợ: dư nợ trong event là ảnh
     * lúc khớp lệnh, còn Payment tự giảm dư nợ mỗi lần phân phối repayment ({@code repayPrincipal}).
     * Event đổi chủ đến sau một lần phân phối mà ghi đè sẽ dựng lại dư nợ cũ (cao hơn thật) và các
     * kỳ sau chia gốc vượt dư nợ.
     */
    public void apply(String investorId, BigDecimal outstandingPrincipal, String currency,
            Instant changedAt, String sourceReference, Instant now) {
        if (ownershipChangedAt != null && changedAt.isBefore(ownershipChangedAt)) return;
        this.investorId = investorId;
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
