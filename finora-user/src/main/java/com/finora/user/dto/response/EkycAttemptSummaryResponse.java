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
 * @param decidedAt     thời điểm nhân viên duyệt hoặc từ chối; {@code null} khi chưa có quyết định của người
 * @param reviewNote    ghi chú của người duyệt; với lượt bị từ chối đây là lý do khách thấy trên app
 * @param aiUnavailable hồ sơ chuyển duyệt tay vì finora-ai gặp sự cố (có mã {@code AI_UNAVAILABLE} ở bước thẻ hoặc
 *                      liveness): nhân viên phải tự đọc thẻ và/hoặc so mặt
 * @param userReportedIncorrect người dùng chụp lại nhiều lần mà vẫn báo thông tin đọc từ thẻ chưa đúng (mã
 *                      {@code USER_REPORTED_INCORRECT}): nhân viên đối chiếu ảnh thẻ và được sửa mọi trường
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
        Instant submittedAt,
        Instant decidedAt,
        String reviewNote,
        boolean aiUnavailable,
        boolean userReportedIncorrect
) {
}
