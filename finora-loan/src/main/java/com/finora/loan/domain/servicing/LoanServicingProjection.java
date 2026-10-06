package com.finora.loan.domain.servicing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Bản chiếu chỉ đọc từ Fineract/snapshot Fineract; không phải sổ dư nợ thứ hai. */
@Entity
@Table(name = "loan_servicing_projections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanServicingProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finora_loan_id", nullable = false, unique = true, updatable = false)
    private Long finoraLoanId;

    @Column(name = "fineract_status_code", nullable = false, length = 50)
    private String fineractStatusCode;

    @Column(name = "principal_disbursed", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalDisbursed;
    @Column(name = "principal_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalPaid;
    @Column(name = "principal_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalOutstanding;
    @Column(name = "interest_charged", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestCharged;
    @Column(name = "interest_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestPaid;
    @Column(name = "interest_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestOutstanding;
    @Column(name = "fee_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal feeOutstanding;
    @Column(name = "penalty_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal penaltyOutstanding;
    @Column(name = "total_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalOutstanding;
    @Column(name = "overdue_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal overdueAmount;
    @Column(name = "overdue_since")
    private LocalDate overdueSince;
    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue;
    @Column(name = "next_due_date")
    private LocalDate nextDueDate;
    @Column(name = "next_due_amount", precision = 18, scale = 2)
    private BigDecimal nextDueAmount;
    @Column(name = "maturity_date")
    private LocalDate maturityDate;
    @Column(nullable = false, length = 40)
    private String source;
    @Column(name = "data_as_of", nullable = false)
    private Instant dataAsOf;
    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;
    @Column(nullable = false)
    private boolean stale;
    @Column(name = "projection_version", nullable = false)
    private long projectionVersion;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static LoanServicingProjection fromContractSnapshot(
            Long finoraLoanId,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fees,
            BigDecimal penalties,
            BigDecimal totalOutstanding,
            LocalDate nextDueDate,
            BigDecimal nextDueAmount,
            LocalDate maturityDate,
            Instant now
    ) {
        LoanServicingProjection value = new LoanServicingProjection();
        value.finoraLoanId = Objects.requireNonNull(finoraLoanId, "finoraLoanId");
        value.fineractStatusCode = "ACTIVE";
        value.principalDisbursed = money(principal);
        value.principalPaid = zero();
        value.principalOutstanding = money(principal);
        value.interestCharged = money(interest);
        value.interestPaid = zero();
        value.interestOutstanding = money(interest);
        value.feeOutstanding = money(fees);
        value.penaltyOutstanding = money(penalties);
        value.totalOutstanding = money(totalOutstanding);
        value.overdueAmount = zero();
        value.daysPastDue = 0;
        value.nextDueDate = nextDueDate;
        value.nextDueAmount = nextDueAmount == null ? null : money(nextDueAmount);
        value.maturityDate = maturityDate;
        value.source = "FINERACT_CONTRACT_SNAPSHOT";
        value.dataAsOf = Objects.requireNonNull(now, "now");
        value.lastSyncedAt = now;
        // Lịch hợp đồng là bằng chứng hợp lệ nhưng cần refresh từ core sau actual disbursement.
        value.stale = true;
        value.projectionVersion = 1;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void applyRepayment(
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fee,
            BigDecimal penalty,
            BigDecimal corePrincipalOutstanding,
            BigDecimal coreInterestOutstanding,
            BigDecimal coreFeeOutstanding,
            BigDecimal corePenaltyOutstanding,
            BigDecimal coreTotalOutstanding,
            BigDecimal coreOverdueAmount,
            LocalDate coreNextDueDate,
            BigDecimal coreNextDueAmount,
            Instant occurredAt
    ) {
        principalPaid = principalPaid.add(money(principal));
        interestPaid = interestPaid.add(money(interest));
        principalOutstanding = money(corePrincipalOutstanding);
        interestOutstanding = money(coreInterestOutstanding);
        feeOutstanding = money(coreFeeOutstanding);
        penaltyOutstanding = money(corePenaltyOutstanding);
        totalOutstanding = money(coreTotalOutstanding);
        overdueAmount = money(coreOverdueAmount);
        if (overdueAmount.signum() == 0) {
            overdueSince = null;
            daysPastDue = 0;
        }
        nextDueDate = coreNextDueDate;
        nextDueAmount = coreNextDueAmount == null ? null : money(coreNextDueAmount);
        if (totalOutstanding.signum() == 0) fineractStatusCode = "CLOSED_OBLIGATIONS_MET";
        source = "FINERACT_REPAYMENT_EVENT";
        dataAsOf = occurredAt;
        lastSyncedAt = occurredAt;
        stale = false;
        projectionVersion++;
        updatedAt = occurredAt;
    }

    public void applyCoreSnapshot(LoanServicingSnapshot snapshot, Instant syncedAt) {
        fineractStatusCode = requireText(snapshot.statusCode(), "statusCode");
        principalDisbursed = money(snapshot.principalDisbursed());
        principalPaid = money(snapshot.principalPaid());
        principalOutstanding = money(snapshot.principalOutstanding());
        interestCharged = money(snapshot.interestCharged());
        interestPaid = money(snapshot.interestPaid());
        interestOutstanding = money(snapshot.interestOutstanding());
        feeOutstanding = money(snapshot.feeOutstanding());
        penaltyOutstanding = money(snapshot.penaltyOutstanding());
        totalOutstanding = money(snapshot.totalOutstanding());
        overdueAmount = money(snapshot.overdueAmount());
        overdueSince = overdueAmount.signum() == 0 ? null : snapshot.overdueSince();
        daysPastDue = overdueAmount.signum() == 0 ? 0 : Math.max(0, snapshot.daysPastDue());
        nextDueDate = snapshot.nextDueDate();
        nextDueAmount = snapshot.nextDueAmount() == null ? null : money(snapshot.nextDueAmount());
        maturityDate = snapshot.maturityDate();
        source = "FINERACT_SERVICING_SYNC";
        dataAsOf = syncedAt;
        lastSyncedAt = syncedAt;
        stale = false;
        projectionVersion++;
        updatedAt = syncedAt;
    }

    public void markStale(Instant now) {
        stale = true;
        updatedAt = now;
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "money").setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " không được trống");
        return value.trim();
    }
}
