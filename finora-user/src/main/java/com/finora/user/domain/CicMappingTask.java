package com.finora.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Hàng đợi bền vững để đăng ký ánh xạ borrowerId sang CIC sau eKYC. */
@Entity
@Table(name = "user_cic_mapping_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CicMappingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_profile_id", nullable = false, unique = true, updatable = false)
    private Long userProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CicMappingTaskStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CicMappingTask pending(Long userProfileId, Instant now) {
        if (userProfileId == null || now == null) {
            throw new IllegalArgumentException("userProfileId và thời điểm tạo là bắt buộc");
        }
        CicMappingTask task = new CicMappingTask();
        task.userProfileId = userProfileId;
        task.status = CicMappingTaskStatus.PENDING;
        task.nextAttemptAt = now;
        task.createdAt = now;
        task.updatedAt = now;
        return task;
    }

    public boolean isDue(Instant now) {
        return switch (status) {
            case PENDING, PROCESSING, RETRY_PENDING -> !nextAttemptAt.isAfter(now);
            case COMPLETED, DEAD -> false;
        };
    }

    /** PROCESSING có lease để worker khác có thể cứu việc dang dở sau khi process chết. */
    public void claim(Instant now, Duration leaseDuration) {
        if (!isDue(now)) {
            throw new IllegalStateException("CIC mapping task chưa đến hạn xử lý");
        }
        status = CicMappingTaskStatus.PROCESSING;
        attemptCount++;
        nextAttemptAt = now.plus(leaseDuration);
        lastErrorCode = null;
        updatedAt = now;
    }

    public void complete(Instant now) {
        requireProcessing();
        status = CicMappingTaskStatus.COMPLETED;
        completedAt = now;
        // Cột bắt buộc; giá trị này không còn được dùng vì COMPLETED không nằm trong truy vấn due.
        nextAttemptAt = now;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void fail(String errorCode, boolean retryable, int maxAttempts, Duration retryDelay, Instant now) {
        requireProcessing();
        lastErrorCode = normalizeErrorCode(errorCode);
        boolean exhausted = attemptCount >= maxAttempts;
        status = retryable && !exhausted
                ? CicMappingTaskStatus.RETRY_PENDING
                : CicMappingTaskStatus.DEAD;
        nextAttemptAt = status == CicMappingTaskStatus.RETRY_PENDING
                ? now.plus(retryDelay)
                : now;
        updatedAt = now;
    }

    private void requireProcessing() {
        if (status != CicMappingTaskStatus.PROCESSING) {
            throw new IllegalStateException("CIC mapping task không ở trạng thái PROCESSING");
        }
    }

    private static String normalizeErrorCode(String errorCode) {
        String value = errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode.trim();
        return value.substring(0, Math.min(value.length(), 80));
    }
}
