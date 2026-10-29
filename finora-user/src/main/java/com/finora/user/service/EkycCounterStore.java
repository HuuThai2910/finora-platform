package com.finora.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Bộ đếm ngắn hạn của luồng eKYC trên Redis.
 * <ul>
 *   <li>{@code ekyc_retake_count:<uid>} (1 giờ): số lần phải chụp lại thẻ; gửi cho AI để sau 2 lần AI
 *       chuyển mâu thuẫn dai dẳng sang chờ duyệt thay vì bắt chụp mãi.</li>
 *   <li>{@code ekyc_liveness_fail:<uid>} (24 giờ từ lần trượt đầu): trượt liveness 3 lần thì hồ sơ vào
 *       duyệt tay — tín hiệu gian lận đáng để người xem, nhưng không khoá người dùng.</li>
 *   <li>{@code ekyc_media_sha:<sha256>} (30 ngày): video/bộ ảnh đã từng gửi; gửi lại đúng file cũ là
 *       dấu hiệu phát lại.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EkycCounterStore {

    public static final Duration RETAKE_TTL = Duration.ofHours(1);
    public static final Duration LIVENESS_FAIL_TTL = Duration.ofHours(24);
    public static final Duration MEDIA_SHA_TTL = Duration.ofDays(30);

    /** Số lần trượt liveness tối đa trước khi chuyển duyệt tay. */
    public static final int MAX_LIVENESS_FAILURES = 3;

    private static final String KEY_RETAKE = "ekyc_retake_count:";
    private static final String KEY_LIVENESS_FAIL = "ekyc_liveness_fail:";
    private static final String KEY_MEDIA_SHA = "ekyc_media_sha:";

    private final StringRedisTemplate redisTemplate;

    public int currentRetakeCount(UUID keycloakUserId) {
        String raw = redisTemplate.opsForValue().get(KEY_RETAKE + keycloakUserId);
        try {
            return raw == null ? 0 : Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Tăng số lần chụp lại; TTL làm mới mỗi lần để chuỗi chụp lại liên tiếp không bị cắt giữa chừng. */
    public void incrementRetakeCount(UUID keycloakUserId) {
        String key = KEY_RETAKE + keycloakUserId;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, RETAKE_TTL);
    }

    public void resetRetakeCount(UUID keycloakUserId) {
        redisTemplate.delete(KEY_RETAKE + keycloakUserId);
    }

    /** Ghi một lần trượt; cửa sổ 24 giờ tính từ lần trượt đầu tiên. Trả tổng số lần trượt hiện tại. */
    public int recordLivenessFailure(UUID keycloakUserId) {
        String key = KEY_LIVENESS_FAIL + keycloakUserId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, LIVENESS_FAIL_TTL);
        }
        return count == null ? 1 : count.intValue();
    }

    public void resetLivenessFailures(UUID keycloakUserId) {
        redisTemplate.delete(KEY_LIVENESS_FAIL + keycloakUserId);
    }

    /**
     * Đăng ký hash của video/bộ ảnh bằng {@code SET NX}: nguyên tử nên hai request gửi cùng file đồng thời
     * cũng chỉ một request được nhận.
     *
     * @return {@code true} nếu file chưa từng được gửi trong 30 ngày
     */
    public boolean registerMedia(String sha256Hex) {
        Boolean fresh = redisTemplate.opsForValue().setIfAbsent(KEY_MEDIA_SHA + sha256Hex, "1", MEDIA_SHA_TTL);
        return Boolean.TRUE.equals(fresh);
    }
}
