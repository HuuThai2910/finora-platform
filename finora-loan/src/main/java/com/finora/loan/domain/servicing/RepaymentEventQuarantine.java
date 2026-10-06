package com.finora.loan.domain.servicing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Giữ event hợp lệ đến trước mapping Loan; payload chỉ chứa logical ID và số liệu servicing. */
@Entity
@Table(name = "loan_repayment_event_quarantine")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepaymentEventQuarantine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, updatable = false) private UUID eventId;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "repayment_id", nullable = false, updatable = false) private UUID repaymentId;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false) private String payloadJson;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RepaymentEventQuarantineStatus status;
    @Column(name = "reason_code", nullable = false, length = 60) private String reasonCode;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "last_attempt_at") private Instant lastAttemptAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private Long version;

    public static RepaymentEventQuarantine pending(UUID eventId, Long applicationId, UUID repaymentId,
            String payloadJson, Instant receivedAt, Instant now) {
        RepaymentEventQuarantine value = new RepaymentEventQuarantine();
        value.eventId = eventId;
        value.loanApplicationId = applicationId;
        value.repaymentId = repaymentId;
        value.payloadJson = payloadJson;
        value.receivedAt = receivedAt;
        value.status = RepaymentEventQuarantineStatus.PENDING;
        value.reasonCode = "FINORA_LOAN_MAPPING_MISSING";
        value.attemptCount = 0;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void attempted(Instant now) {
        if (status != RepaymentEventQuarantineStatus.PENDING) return;
        attemptCount++;
        lastAttemptAt = now;
        updatedAt = now;
    }

    public void resolve(Instant now) {
        status = RepaymentEventQuarantineStatus.RESOLVED;
        resolvedAt = now;
        updatedAt = now;
    }
}
