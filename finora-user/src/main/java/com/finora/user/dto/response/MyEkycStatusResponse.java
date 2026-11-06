package com.finora.user.dto.response;

import com.finora.common.enums.user.EkycStatus;

import java.time.Instant;

/**
 * Trạng thái xác minh danh tính của chính người dùng, cho app hiện kết quả lượt gần nhất.
 *
 * @param ekycStatus          trạng thái hồ sơ hiện tại
 * @param latestAttemptStatus trạng thái lượt gần nhất ({@code IN_PROGRESS}, {@code MANUAL_REVIEW}, {@code REJECTED}…);
 *                            {@code null} khi chưa xác minh lần nào
 * @param submittedAt         thời điểm gửi lượt gần nhất
 * @param decidedAt           thời điểm nhân viên duyệt hoặc từ chối lượt gần nhất
 * @param rejectionReason     lý do nhân viên ghi khi từ chối; chỉ có khi lượt gần nhất bị từ chối
 */
public record MyEkycStatusResponse(
        EkycStatus ekycStatus,
        String latestAttemptStatus,
        Instant submittedAt,
        Instant decidedAt,
        String rejectionReason
) {
}
