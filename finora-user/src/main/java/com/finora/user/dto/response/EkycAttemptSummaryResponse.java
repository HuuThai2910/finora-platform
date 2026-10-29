package com.finora.user.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một dòng trong trang Duyệt eKYC của admin (EKYC-002 mục 3.7).
 *
 * @param attemptId     mã công khai của lượt
 * @param faceMatchZone {@code HIGH | GRAY | LOW}, suy từ mã lý do liveness đã lưu; {@code null} khi chưa so mặt
 * @param reasons       mã lý do/cảnh báo của bước đọc thẻ (không có giá trị trường)
 * @param submittedAt   thời điểm gửi duyệt hoặc tự xác minh; {@code null} khi lượt chưa kết thúc
 */
public record EkycAttemptSummaryResponse(
        UUID attemptId,
        Long userProfileId,
        String email,
        String status,
        String cardType,
        String documentDecision,
        String livenessResult,
        Double faceMatchScore,
        String faceMatchZone,
        List<String> reasons,
        Instant createdAt,
        Instant submittedAt
) {
}
