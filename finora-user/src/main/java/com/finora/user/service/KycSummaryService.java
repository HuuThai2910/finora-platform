package com.finora.user.service;

import com.finora.user.dto.response.KycSummaryResponse;
import com.finora.user.dto.response.MyEkycStatusResponse;

import java.util.UUID;

/**
 * Đọc tóm tắt eKYC của chính người dùng (W2-KYC). Dữ liệu lấy từ DB User tại thời điểm gọi, không từ claim trong token.
 */
public interface KycSummaryService {

    KycSummaryResponse summaryOf(UUID keycloakUserId);

    /**
     * Kết quả xác minh gần nhất của chính người dùng, kèm lý do khi nhân viên từ chối — để app báo khách cần làm lại
     * gì. Lý do là chữ nhân viên viết cho khách; nhân viên được báo trước khi gửi.
     */
    MyEkycStatusResponse myEkycStatus(UUID keycloakUserId);
}
