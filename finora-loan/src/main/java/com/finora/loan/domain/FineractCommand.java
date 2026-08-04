package com.finora.loan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fineract_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FineractCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, length = 36, unique = true, updatable = false)
    private String commandId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "command_type", nullable = false, length = 50, updatable = false)
    private FineractCommandType commandType;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "mapping_id", nullable = false, updatable = false)
    private Long mappingId;

    @Column(name = "idempotency_key", nullable = false, length = 150, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String requestSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot_json", columnDefinition = "jsonb")
    private String responseSnapshotJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private FineractCommandStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_detail", length = 1000)
    private String lastErrorDetail;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static FineractCommand pending(
            String commandId,
            FineractCommandType commandType,
            Long aggregateId,
            Long mappingId,
            String idempotencyKey,
            String requestHash,
            String requestSnapshotJson,
            Instant now
    ) {
        FineractCommand command = new FineractCommand();
        command.commandId = requireText(commandId, "commandId");
        command.commandType = Objects.requireNonNull(commandType, "commandType");
        command.aggregateType = "LOAN_PRODUCT";
        command.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        command.mappingId = Objects.requireNonNull(mappingId, "mappingId");
        command.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        command.requestHash = requireText(requestHash, "requestHash");
        command.requestSnapshotJson = requireText(requestSnapshotJson, "requestSnapshotJson");
        command.status = FineractCommandStatus.PENDING;
        command.attemptCount = 0;
        command.createdAt = Objects.requireNonNull(now, "now");
        command.updatedAt = now;
        return command;
    }

    public void markProcessing(Instant now) {
        if (status != FineractCommandStatus.PENDING && status != FineractCommandStatus.RETRY_PENDING) {
            throw new IllegalStateException("Command không ở trạng thái có thể xử lý: " + status);
        }
        status = FineractCommandStatus.PROCESSING;
        attemptCount++;
        nextRetryAt = null;
        updatedAt = now;
    }

    /** Cho phép admin chủ động thử lại lỗi vĩnh viễn sau khi dependency/config đã được sửa. */
    public void reopen(Instant now) {
        if (status != FineractCommandStatus.FAILED) {
            throw new IllegalStateException("Chỉ command FAILED mới được mở lại");
        }
        status = FineractCommandStatus.PENDING;
        lastErrorCode = null;
        lastErrorDetail = null;
        nextRetryAt = null;
        completedAt = null;
        updatedAt = now;
    }

    public void markSucceeded(String responseSnapshotJson, Instant now) {
        this.responseSnapshotJson = responseSnapshotJson;
        status = FineractCommandStatus.SUCCEEDED;
        lastErrorCode = null;
        lastErrorDetail = null;
        nextRetryAt = null;
        completedAt = now;
        updatedAt = now;
    }

    public void markRetryPending(String code, String detail, Instant retryAt, Instant now) {
        status = FineractCommandStatus.RETRY_PENDING;
        lastErrorCode = normalize(code, 100);
        lastErrorDetail = normalize(detail, 1000);
        nextRetryAt = Objects.requireNonNull(retryAt, "retryAt");
        updatedAt = now;
    }

    public void markFailed(String code, String detail, Instant now) {
        status = FineractCommandStatus.FAILED;
        lastErrorCode = normalize(code, 100);
        lastErrorDetail = normalize(detail, 1000);
        nextRetryAt = null;
        completedAt = now;
        updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
