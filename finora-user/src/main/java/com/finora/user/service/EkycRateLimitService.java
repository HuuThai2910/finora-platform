package com.finora.user.service;

import java.time.Duration;
import java.util.UUID;

/**
 * Chặn gọi eKYC dồn dập.
 */
public interface EkycRateLimitService {

    /** Khoảng cách tối thiểu giữa hai lần quét thẻ của cùng một người dùng. */
    Duration VERIFY_MIN_INTERVAL = Duration.ofSeconds(10);

    /** Khoảng cách tối thiểu giữa hai lần gửi video liveness. */
    Duration LIVENESS_MIN_INTERVAL = Duration.ofSeconds(10);

    /**
     * Khoảng cách giữa hai lần xin thử thách: ngắn vì app xin lại ngay khi quay video lỗi và chuyển sang
     * chế độ ảnh tĩnh; đủ để chặn vòng lặp xin liên tục.
     */
    Duration CHALLENGE_MIN_INTERVAL = Duration.ofSeconds(3);

    /** Chiếm suất quét thẻ; {@code false} nếu lần gọi trước còn trong khoảng chờ. */
    boolean tryAcquireVerifySlot(UUID keycloakUserId);

    /** Trả suất quét thẻ khi AI lỗi để người dùng thử lại ngay. */
    void releaseVerifySlot(UUID keycloakUserId);

    boolean tryAcquireLivenessSlot(UUID keycloakUserId);

    void releaseLivenessSlot(UUID keycloakUserId);

    boolean tryAcquireChallengeSlot(UUID keycloakUserId);
}
