package com.finora.user.service;

import com.finora.user.dto.response.KycSummaryResponse;

import java.util.UUID;

/**
 * Đọc tóm tắt eKYC của chính người dùng (W2-KYC). Dữ liệu lấy từ DB User tại thời điểm gọi, không từ claim trong token.
 */
public interface KycSummaryService {

    KycSummaryResponse summaryOf(UUID keycloakUserId);
}
