package com.finora.user.service;

/**
 * Worker định kỳ xoá hẳn giá trị và bộ đếm tạm đã hết hạn của {@link TemporaryStore}
 * (mặc định mỗi 5 phút, đổi bằng {@code finora.temporary-store.cleanup-delay-ms}).
 */
public interface TemporaryStoreCleanupWorker {

    /** Một vòng dọn; lỗi database chỉ ghi log để vòng sau thử lại. */
    void run();
}
