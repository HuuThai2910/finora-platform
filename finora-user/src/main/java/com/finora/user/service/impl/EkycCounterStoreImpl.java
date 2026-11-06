package com.finora.user.service.impl;

import com.finora.user.service.EkycCounterStore;
import com.finora.user.service.TemporaryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Bộ đếm ngắn hạn của luồng eKYC trong kho tạm ({@link TemporaryStore}).
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
public class EkycCounterStoreImpl implements EkycCounterStore {

    private static final String KEY_RETAKE = "ekyc_retake_count:";
    private static final String KEY_LIVENESS_FAIL = "ekyc_liveness_fail:";
    private static final String KEY_MEDIA_SHA = "ekyc_media_sha:";

    private final TemporaryStore temporaryStore;

    @Override
    public int currentRetakeCount(UUID keycloakUserId) {
        long count = temporaryStore.findCounter(KEY_RETAKE + keycloakUserId);
        return (int) Math.max(0, Math.min(count, Integer.MAX_VALUE));
    }

    @Override
    public void incrementRetakeCount(UUID keycloakUserId) {
        temporaryStore.incrementRefreshingTtl(KEY_RETAKE + keycloakUserId, RETAKE_TTL);
    }

    @Override
    public void resetRetakeCount(UUID keycloakUserId) {
        temporaryStore.deleteCounter(KEY_RETAKE + keycloakUserId);
    }

    @Override
    public int recordLivenessFailure(UUID keycloakUserId) {
        return (int) temporaryStore.incrementWithinWindow(KEY_LIVENESS_FAIL + keycloakUserId, LIVENESS_FAIL_TTL);
    }

    @Override
    public void resetLivenessFailures(UUID keycloakUserId) {
        temporaryStore.deleteCounter(KEY_LIVENESS_FAIL + keycloakUserId);
    }

    @Override
    public boolean registerMedia(String sha256Hex) {
        return temporaryStore.putIfAbsent(KEY_MEDIA_SHA + sha256Hex, "1", MEDIA_SHA_TTL);
    }
}
