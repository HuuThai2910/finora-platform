package com.finora.user.service;

import java.util.UUID;

/**
 * Dọn trạng thái tạm của một phiên eKYC trong kho tạm (bản nháp, thử thách, bộ đếm) sau khi kết quả cuối
 * đã commit vào database.
 */
public interface EkycSessionCleanup {

    void clearAll(UUID keycloakUserId);
}
