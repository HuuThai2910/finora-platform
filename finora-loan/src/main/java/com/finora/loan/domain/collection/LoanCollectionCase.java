package com.finora.loan.domain.collection;

import com.finora.loan.domain.servicing.DebtGroup;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "loan_collection_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanCollectionCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "case_id", nullable = false, unique = true, updatable = false) private UUID caseId;
    @Column(name = "finora_loan_id", nullable = false, updatable = false) private Long finoraLoanId;
    @Column(name = "loan_number", nullable = false, length = 50, updatable = false) private String loanNumber;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false) private String borrowerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private CollectionStage stage;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private CollectionCaseStatus status;
    @Column(name = "days_past_due", nullable = false) private int daysPastDue;
    @Column(name = "debt_group", nullable = false) private int debtGroup;
    @Column(name = "overdue_amount", nullable = false, precision = 18, scale = 2) private BigDecimal overdueAmount;
    @Column(name = "total_outstanding", nullable = false, precision = 18, scale = 2) private BigDecimal totalOutstanding;
    @Column(name = "overdue_since") private LocalDate overdueSince;
    @Column(name = "opened_at", nullable = false, updatable = false) private Instant openedAt;
    @Column(name = "last_observed_at", nullable = false) private Instant lastObservedAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static LoanCollectionCase open(Long loanId, String loanNumber, String borrowerId, int dpd,
            BigDecimal overdue, BigDecimal outstanding, LocalDate overdueSince, Instant now) {
        LoanCollectionCase value = new LoanCollectionCase();
        value.caseId = UUID.randomUUID();
        value.finoraLoanId = Objects.requireNonNull(loanId);
        value.loanNumber = requireText(loanNumber);
        value.borrowerId = requireText(borrowerId);
        value.status = CollectionCaseStatus.OPEN;
        value.openedAt = now;
        value.createdAt = now;
        value.observe(dpd, overdue, outstanding, overdueSince, now);
        return value;
    }

    public void observe(int dpd, BigDecimal overdue, BigDecimal outstanding, LocalDate since, Instant now) {
        if (status != CollectionCaseStatus.OPEN) throw new IllegalStateException("Collection case đã đóng");
        stage = CollectionStage.fromDaysPastDue(dpd);
        daysPastDue = dpd;
        debtGroup = DebtGroup.fromDaysPastDue(dpd);
        overdueAmount = money(overdue);
        totalOutstanding = money(outstanding);
        overdueSince = since;
        lastObservedAt = now;
        updatedAt = now;
    }

    public void close(CollectionCaseStatus terminalStatus, Instant now) {
        if (terminalStatus == CollectionCaseStatus.OPEN) throw new IllegalArgumentException("Trạng thái đóng không hợp lệ");
        if (status != CollectionCaseStatus.OPEN) return;
        status = terminalStatus;
        daysPastDue = 0;
        overdueAmount = BigDecimal.ZERO.setScale(2);
        closedAt = now;
        lastObservedAt = now;
        updatedAt = now;
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value).setScale(2, RoundingMode.HALF_UP);
    }
    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Giá trị bắt buộc bị trống");
        return value.trim();
    }
}

