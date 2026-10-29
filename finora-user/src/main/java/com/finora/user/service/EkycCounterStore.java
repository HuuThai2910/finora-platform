package com.finora.user.service;

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
public interface EkycCounterStore {

    Duration RETAKE_TTL = Duration.ofHours(1);

    Duration LIVENESS_FAIL_TTL = Duration.ofHours(24);

    Duration MEDIA_SHA_TTL = Duration.ofDays(30);

    /** Số lần trượt liveness tối đa trước khi chuyển duyệt tay. */
    int MAX_LIVENESS_FAILURES = 3;

    int currentRetakeCount(UUID keycloakUserId);

    /** Tăng số lần chụp lại; TTL làm mới mỗi lần để chuỗi chụp lại liên tiếp không bị cắt giữa chừng. */
    void incrementRetakeCount(UUID keycloakUserId);

    void resetRetakeCount(UUID keycloakUserId);

    /** Ghi một lần trượt; cửa sổ 24 giờ tính từ lần trượt đầu tiên. Trả tổng số lần trượt hiện tại. */
    int recordLivenessFailure(UUID keycloakUserId);

    void resetLivenessFailures(UUID keycloakUserId);

    /**
     * Đăng ký hash của video/bộ ảnh bằng {@code SET NX}: nguyên tử nên hai request gửi cùng file đồng thời
     * cũng chỉ một request được nhận.
     *
     * @return {@code true} nếu file chưa từng được gửi trong 30 ngày
     */
    boolean registerMedia(String sha256Hex);
}
