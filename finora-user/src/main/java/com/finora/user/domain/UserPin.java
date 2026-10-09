package com.finora.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mã PIN giao dịch của một người dùng — ánh xạ bảng {@code user_pins}.
 * <p>
 * Nhập sai {@link #MAX_FAILED_ATTEMPTS} lần liên tiếp thì khoá {@link #LOCK_DURATION};
 * hết khoá được thử lại đủ số lần từ đầu.
 */
@Entity
@Table(name = "user_pins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPin {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Id
    @Column(name = "keycloak_user_id")
    private UUID keycloakUserId;

    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserPin create(UUID keycloakUserId, String pinHash, Instant now) {
        UserPin pin = new UserPin();
        pin.keycloakUserId = keycloakUserId;
        pin.pinHash = pinHash;
        pin.createdAt = now;
        pin.updatedAt = now;
        return pin;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Số lần còn được nhập sai trước khi bị khoá. */
    public int remainingAttempts(Instant now) {
        return isLocked(now) ? 0 : MAX_FAILED_ATTEMPTS - failedAttempts;
    }

    /**
     * Ghi nhận một lần nhập sai.
     *
     * @return {@code true} nếu lần sai này làm PIN bị khoá
     */
    public boolean recordFailure(Instant now) {
        failedAttempts++;
        updatedAt = now;
        if (failedAttempts < MAX_FAILED_ATTEMPTS) {
            return false;
        }
        failedAttempts = 0;
        lockedUntil = now.plus(LOCK_DURATION);
        return true;
    }

    public void recordSuccess(Instant now) {
        if (failedAttempts != 0 || lockedUntil != null) {
            failedAttempts = 0;
            lockedUntil = null;
            updatedAt = now;
        }
    }

    /** Đặt PIN mới và gỡ khoá — dùng cho đổi PIN và quên PIN. */
    public void replaceHash(String pinHash, Instant now) {
        this.pinHash = pinHash;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }
}
