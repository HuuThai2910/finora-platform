package com.finora.loan.domain.servicing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Hồ sơ sai lệch servicing append-by-observation; không phải API sửa số dư. */
@Entity
@Table(name = "loan_reconciliation_incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanReconciliationIncident {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "incident_id", nullable = false, unique = true, updatable = false) private UUID incidentId;
    @Column(name = "finora_loan_id", nullable = false, updatable = false) private Long finoraLoanId;
    @Column(name = "loan_number", nullable = false, length = 50, updatable = false) private String loanNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private ReconciliationIncidentType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReconciliationIncidentStatus status;
    @Column(name = "expected_value", nullable = false, length = 200) private String expectedValue;
    @Column(name = "actual_value", nullable = false, length = 200) private String actualValue;
    @Column(name = "occurrence_count", nullable = false) private int occurrenceCount;
    @Column(name = "first_detected_at", nullable = false, updatable = false) private Instant firstDetectedAt;
    @Column(name = "last_detected_at", nullable = false) private Instant lastDetectedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolution_code", length = 60) private String resolutionCode;
    @Column(name = "resolved_by", length = 100) private String resolvedBy;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static LoanReconciliationIncident open(Long loanId, String loanNumber,
            ReconciliationIncidentType type, String expected, String actual, Instant now) {
        LoanReconciliationIncident value = new LoanReconciliationIncident();
        value.incidentId = UUID.randomUUID();
        value.finoraLoanId = Objects.requireNonNull(loanId);
        value.loanNumber = required(loanNumber, 50);
        value.type = Objects.requireNonNull(type);
        value.status = ReconciliationIncidentStatus.OPEN;
        value.expectedValue = required(expected, 200);
        value.actualValue = required(actual, 200);
        value.occurrenceCount = 1;
        value.firstDetectedAt = Objects.requireNonNull(now);
        value.lastDetectedAt = now;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void observe(String expected, String actual, Instant now) {
        if (status != ReconciliationIncidentStatus.OPEN) {
            throw new IllegalStateException("Incident đã đóng");
        }
        expectedValue = required(expected, 200);
        actualValue = required(actual, 200);
        occurrenceCount++;
        lastDetectedAt = Objects.requireNonNull(now);
        updatedAt = now;
    }

    public void resolve(String code, String actor, Instant now) {
        if (status == ReconciliationIncidentStatus.RESOLVED) return;
        status = ReconciliationIncidentStatus.RESOLVED;
        resolutionCode = required(code, 60);
        resolvedBy = required(actor, 100);
        resolvedAt = Objects.requireNonNull(now);
        updatedAt = now;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Giá trị bắt buộc bị trống");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Giá trị quá dài");
        return normalized;
    }
}
