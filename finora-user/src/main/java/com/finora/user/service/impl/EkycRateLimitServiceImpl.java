package com.finora.user.service.impl;

import com.finora.user.service.EkycRateLimitService;
import com.finora.user.service.TemporaryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Chặn gọi eKYC dồn dập.
 * <p>
 * Đọc thẻ và chấm liveness đều chạy phân tích nặng ở {@code finora-ai} (AI chỉ xử lý một phân tích tại
 * một thời điểm), nên mỗi người dùng chỉ được một lần gọi trong mỗi khoảng chờ. Suất gọi là một giá trị tự
 * hết hạn trong kho tạm, chiếm bằng {@link TemporaryStore#putIfAbsent} (nguyên tử như {@code SET NX}); khi AI
 * lỗi, suất được trả lại ngay vì lỗi không phải do người dùng. Database lỗi chỉ làm request lỗi hệ thống (như
 * mất Redis trước đây), không làm sai trạng thái nghiệp vụ; không nuốt lỗi để cho qua vì như vậy là bỏ giới
 * hạn gọi AI.
 */
@Service
@RequiredArgsConstructor
public class EkycRateLimitServiceImpl implements EkycRateLimitService {

    private static final String KEY_VERIFY_RATE = "ekyc_verify_rate:";
    private static final String KEY_LIVENESS_RATE = "ekyc_liveness_rate:";
    private static final String KEY_CHALLENGE_RATE = "ekyc_challenge_rate:";

    private final TemporaryStore temporaryStore;

    @Override
    public boolean tryAcquireVerifySlot(UUID keycloakUserId) {
        return acquire(KEY_VERIFY_RATE + keycloakUserId, VERIFY_MIN_INTERVAL);
    }

    @Override
    public void releaseVerifySlot(UUID keycloakUserId) {
        temporaryStore.delete(KEY_VERIFY_RATE + keycloakUserId);
    }

    @Override
    public boolean tryAcquireLivenessSlot(UUID keycloakUserId) {
        return acquire(KEY_LIVENESS_RATE + keycloakUserId, LIVENESS_MIN_INTERVAL);
    }

    @Override
    public void releaseLivenessSlot(UUID keycloakUserId) {
        temporaryStore.delete(KEY_LIVENESS_RATE + keycloakUserId);
    }

    @Override
    public boolean tryAcquireChallengeSlot(UUID keycloakUserId) {
        return acquire(KEY_CHALLENGE_RATE + keycloakUserId, CHALLENGE_MIN_INTERVAL);
    }

    private boolean acquire(String key, Duration interval) {
        return temporaryStore.putIfAbsent(key, "1", interval);
    }
}
