package com.finora.loan.domain.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private Integer eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_token")
    private UUID processingToken;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "last_error_detail", length = 500)
    private String lastErrorDetail;

    @Column(name = "trace_id", nullable = false, length = 100, updatable = false)
    private String traceId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static OutboxEvent create(
            UUID eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String payloadJson,
            String traceId,
            Instant now
    ) {
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion phải dương");
        }
        OutboxEvent event = new OutboxEvent();
        event.eventId = Objects.requireNonNull(eventId, "eventId");
        event.aggregateType = requireText(aggregateType, "aggregateType");
        event.aggregateId = requireText(aggregateId, "aggregateId");
        event.eventType = requireText(eventType, "eventType");
        event.eventVersion = eventVersion;
        event.payloadJson = requireText(payloadJson, "payloadJson");
        event.status = OutboxEventStatus.PENDING;
        event.attemptCount = 0;
        event.availableAt = Objects.requireNonNull(now, "now");
        event.traceId = requireText(traceId, "traceId");
        event.createdAt = now;
        event.updatedAt = now;
        return event;
    }

    /** Claim có lease để instance khác tiếp quản nếu process dừng sau commit nhưng trước publish. */
    public boolean claim(Instant now, Instant leaseExpiredBefore, UUID claimToken) {
        boolean duePending = status == OutboxEventStatus.PENDING && !availableAt.isAfter(now);
        boolean abandoned = status == OutboxEventStatus.PROCESSING
                && processingStartedAt != null
                && !processingStartedAt.isAfter(leaseExpiredBefore);
        if (!duePending && !abandoned) {
            return false;
        }
        status = OutboxEventStatus.PROCESSING;
        attemptCount += 1;
        processingStartedAt = now;
        processingToken = Objects.requireNonNull(claimToken, "claimToken");
        publishedAt = null;
        updatedAt = now;
        return true;
    }

    public void markPublished(UUID claimToken, Instant now) {
        requireClaim(claimToken);
        status = OutboxEventStatus.PUBLISHED;
        processingToken = null;
        publishedAt = now;
        lastErrorCode = null;
        lastErrorDetail = null;
        updatedAt = now;
    }

    public void markPublishFailure(
            String errorCode,
            String errorDetail,
            boolean retryable,
            int maxAttempts,
            Instant nextAttemptAt,
            UUID claimToken,
            Instant now
    ) {
        requireClaim(claimToken);
        lastErrorCode = requireText(errorCode, "errorCode");
        lastErrorDetail = truncate(errorDetail, 500);
        if (!retryable || attemptCount >= maxAttempts) {
            status = OutboxEventStatus.DEAD_LETTER;
            processingToken = null;
        } else {
            status = OutboxEventStatus.PENDING;
            availableAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
            processingStartedAt = null;
            processingToken = null;
        }
        updatedAt = now;
    }

    private void requireClaim(UUID claimToken) {
        if (status != OutboxEventStatus.PROCESSING || !Objects.equals(processingToken, claimToken)) {
            throw new IllegalStateException("Outbox event không còn thuộc claim hiện tại");
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
