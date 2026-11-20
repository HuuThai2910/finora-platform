package com.finora.user.service.impl;

import com.finora.user.config.EkycManualReviewProperties;
import com.finora.user.service.EkycAiOutageService;
import com.finora.user.service.EkycCounterStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Bộ đếm lỗi AI liên tiếp trong kho tạm ({@code ekyc_ai_failure:<uid>}) và ngưỡng mở lối duyệt tay.
 * <p>
 * Kho tạm lỗi khi ghi nhận "AI trả lời được" thì chỉ log: bộ đếm tự hết hạn theo cửa sổ, và lỗi này không được
 * làm hỏng kết quả AI người dùng vừa nhận.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EkycAiOutageServiceImpl implements EkycAiOutageService {

    private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

    private final EkycCounterStore ekycCounterStore;
    private final EkycManualReviewProperties ekycManualReviewProperties;

    @Override
    public boolean recordFailure(UUID keycloakUserId) {
        return ekycCounterStore.recordAiFailure(keycloakUserId, window()) >= threshold();
    }

    @Override
    public void recordSuccess(UUID keycloakUserId) {
        try {
            ekycCounterStore.resetAiFailures(keycloakUserId);
        } catch (DataAccessException e) {
            log.warn("Chưa xoá được bộ đếm lỗi AI, bộ đếm sẽ tự hết hạn: keycloakUserId={}", keycloakUserId);
        }
    }

    @Override
    public boolean isManualReviewAvailable(UUID keycloakUserId) {
        return ekycCounterStore.currentAiFailures(keycloakUserId) >= threshold();
    }

    int threshold() {
        return Math.max(1, ekycManualReviewProperties.getAiFailureThreshold());
    }

    private Duration window() {
        Duration window = ekycManualReviewProperties.getAiFailureWindow();
        return window == null || window.compareTo(MIN_WINDOW) < 0 ? MIN_WINDOW : window;
    }
}
