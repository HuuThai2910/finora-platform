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
 * Dọn trạng thái tạm của một phiên eKYC trong kho tạm (bản nháp, thử thách, bộ đếm) sau khi kết quả cuối
 * đã commit vào database.
 * <p>
 * Chỉ gọi sau commit: dọn trước mà DB rollback thì người dùng mất bản nháp dù hồ sơ chưa được ghi.
 * Lỗi database ở đây không làm hỏng kết quả đã commit — các bản ghi tạm đều tự hết hạn (đọc bỏ qua bản quá
 * hạn, worker dọn xoá hẳn), nên nuốt lỗi và chỉ ghi log là an toàn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EkycSessionCleanupImpl implements EkycSessionCleanup {

    private final EkycDraftStore ekycDraftStore;
    private final EkycChallengeStore ekycChallengeStore;
    private final EkycCounterStore ekycCounterStore;

    @Override
    public void clearAll(UUID keycloakUserId) {
        try {
            ekycDraftStore.remove(keycloakUserId);
            ekycChallengeStore.remove(keycloakUserId);
            ekycCounterStore.resetRetakeCount(keycloakUserId);
            ekycCounterStore.resetLivenessFailures(keycloakUserId);
        } catch (DataAccessException e) {
            log.warn("Chưa dọn được phiên eKYC trong kho tạm, các bản ghi sẽ tự hết hạn: keycloakUserId={}",
                    keycloakUserId);
        }
    }
}
