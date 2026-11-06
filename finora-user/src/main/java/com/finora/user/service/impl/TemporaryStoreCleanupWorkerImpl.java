package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import com.finora.user.service.TemporaryStoreCleanupWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ xoá hẳn giá trị và bộ đếm tạm đã hết hạn của {@link TemporaryStore}
 * (mặc định mỗi 5 phút, đổi bằng {@code finora.temporary-store.cleanup-delay-ms}).
 * <p>
 * Đọc đã bỏ qua dòng quá hạn nên worker không quyết định đúng sai nghiệp vụ; nó chỉ giữ bảng nhỏ và bảo đảm
 * dữ liệu nhạy cảm không nằm lại sau hạn — bản nháp eKYC chứa số định danh và embedding khuôn mặt (đã mã hoá).
 * Luôn bật, không có cờ tắt. Chạy song song nhiều instance vẫn an toàn vì chỉ là {@code DELETE} theo hạn.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TemporaryStoreCleanupWorkerImpl implements TemporaryStoreCleanupWorker {

    private final TemporaryStore temporaryStore;

    @Override
    @Scheduled(fixedDelayString = "${finora.temporary-store.cleanup-delay-ms:300000}",
            initialDelayString = "${finora.temporary-store.cleanup-initial-delay-ms:60000}")
    public void run() {
        try {
            int deletedRows = temporaryStore.deleteExpired();
            if (deletedRows > 0) {
                log.debug("Đã xoá {} dòng trạng thái tạm hết hạn", deletedRows);
            }
        } catch (DataAccessException e) {
            log.warn("Chưa dọn được trạng thái tạm hết hạn, sẽ thử lại vòng sau: cause={}",
                    e.getClass().getSimpleName());
        }
    }
}
