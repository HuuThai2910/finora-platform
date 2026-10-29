package com.finora.user.service.impl;

import com.finora.user.service.EkycChallengeStore;
import com.finora.user.service.EkycCounterStore;
import com.finora.user.service.EkycDraftStore;
import com.finora.user.service.EkycSessionCleanup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dọn trạng thái tạm của một phiên eKYC trên Redis (bản nháp, thử thách, bộ đếm) sau khi kết quả cuối
 * đã commit vào database.
 * <p>
 * Chỉ gọi sau commit: dọn trước mà DB rollback thì người dùng mất bản nháp dù hồ sơ chưa được ghi.
 * Lỗi Redis ở đây không làm hỏng kết quả đã commit — các key đều tự hết hạn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EkycSessionCleanupImpl implements EkycSessionCleanup {

    private final EkycDraftStore draftStore;
    private final EkycChallengeStore challengeStore;
    private final EkycCounterStore counterStore;

    @Override
    public void clearAll(UUID keycloakUserId) {
        try {
            draftStore.remove(keycloakUserId);
            challengeStore.remove(keycloakUserId);
            counterStore.resetRetakeCount(keycloakUserId);
            counterStore.resetLivenessFailures(keycloakUserId);
        } catch (DataAccessException e) {
            log.warn("Chưa dọn được phiên eKYC trên Redis, các key sẽ tự hết hạn: keycloakUserId={}", keycloakUserId);
        }
    }
}
