package com.finora.loan.domain;

import com.finora.loan.integration.fineract.ScheduleCalculationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "schedule_calculation_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleCalculationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, unique = true, updatable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30, updatable = false)
    private ScheduleCalculationPurpose purpose;

    @Column(name = "request_id", nullable = false, length = 100, unique = true, updatable = false)
    private String requestId;

    @Column(name = "fineract_product_id", nullable = false, updatable = false)
    private Long fineractProductId;

    @Column(name = "expected_disbursement_date", nullable = false, updatable = false)
    private LocalDate expectedDisbursementDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String requestSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "periods_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String periodsSnapshotJson;

    @Column(name = "total_principal", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalPrincipal;

    @Column(name = "total_interest", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalInterest;

    @Column(name = "total_fees", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalFees;

    @Column(name = "total_penalties", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalPenalties;

    @Column(name = "total_repayment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalRepayment;

    @Column(name = "first_installment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal firstInstallment;

    @Column(name = "maximum_installment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal maximumInstallment;

    @Column(name = "response_hash", nullable = false, length = 64, updatable = false)
    private String responseHash;

    @Column(name = "calculation_policy_version", nullable = false, length = 50, updatable = false)
    private String calculationPolicyVersion;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private Instant calculatedAt;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100, updatable = false)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    public static ScheduleCalculationSnapshot submission(
            Long applicationId,
            String requestId,
            Long fineractProductId,
            ScheduleCalculationResult result,
            String actorId,
            Instant now
    ) {
        ScheduleCalculationSnapshot snapshot = new ScheduleCalculationSnapshot();
        snapshot.applicationId = applicationId;
        snapshot.purpose = ScheduleCalculationPurpose.SUBMISSION_SCORING;
        snapshot.requestId = requestId;
        snapshot.fineractProductId = fineractProductId;
        snapshot.expectedDisbursementDate = result.estimatedDisbursementDate();
        snapshot.requestSnapshotJson = result.requestSnapshotJson();
        snapshot.periodsSnapshotJson = result.periodsSnapshotJson();
        snapshot.totalPrincipal = result.totalPrincipal();
        snapshot.totalInterest = result.totalInterest();
        snapshot.totalFees = result.totalFees();
        snapshot.totalPenalties = result.totalPenalties();
        snapshot.totalRepayment = result.totalRepayment();
        snapshot.firstInstallment = result.firstInstallment();
        snapshot.maximumInstallment = result.maximumInstallment();
        snapshot.responseHash = result.responseHash();
        snapshot.calculationPolicyVersion = result.calculationPolicyVersion();
        snapshot.calculatedAt = now;
        snapshot.createdBy = actorId;
        snapshot.updatedBy = actorId;
        snapshot.createdAt = now;
        snapshot.updatedAt = now;
        return snapshot;
    }
}
