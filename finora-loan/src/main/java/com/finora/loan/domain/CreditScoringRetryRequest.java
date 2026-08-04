package com.finora.loan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "credit_scoring_retry_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditScoringRetryRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    @Column(name = "idempotency_key", nullable = false, length = 150, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static CreditScoringRetryRequest create(
            Long applicationId,
            Long assessmentId,
            String idempotencyKey,
            String requestHash,
            String actorId,
            Instant now
    ) {
        CreditScoringRetryRequest request = new CreditScoringRetryRequest();
        request.applicationId = applicationId;
        request.assessmentId = assessmentId;
        request.idempotencyKey = idempotencyKey;
        request.requestHash = requestHash;
        request.createdBy = actorId;
        request.createdAt = now;
        return request;
    }
}
