package com.finora.loan.domain.servicing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Kỳ đã thu tiền sớm ở Payment nhưng chưa tới ngày ghi vào Fineract. */
@Entity
@Table(name = "pending_scheduled_repayments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingScheduledRepayment {
    @Id
    @Column(name = "loan_application_id", updatable = false)
    private Long loanApplicationId;
    @Column(name = "repayment_id", nullable = false, unique = true)
    private UUID repaymentId;
    @Column(name = "borrower_id", nullable = false, length = 100)
    private String borrowerId;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PendingScheduledRepayment collected(Long applicationId, UUID repaymentId, String borrowerId,
            LocalDate dueDate, Instant collectedAt, Instant now) {
        PendingScheduledRepayment value = new PendingScheduledRepayment();
        value.loanApplicationId = Objects.requireNonNull(applicationId, "applicationId");
        value.repaymentId = Objects.requireNonNull(repaymentId, "repaymentId");
        if (borrowerId == null || borrowerId.isBlank()) throw new IllegalArgumentException("borrowerId trống");
        value.borrowerId = borrowerId.trim();
        value.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        value.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
        value.updatedAt = Objects.requireNonNull(now, "now");
        return value;
    }
}
