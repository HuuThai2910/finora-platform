package com.finora.loan.integration.profile.contract;

import java.time.Instant;

/**
 * Response {@code GET /api/v1/users/me/kyc-summary} của finora-user. Chỉ có trạng thái và tuổi; User cố ý không trả họ
 * tên, ngày sinh hay CCCD nên Loan không nhận dữ liệu định danh ({@code LEGAL-DATA-01}).
 *
 * @param userId     ID hồ sơ nội bộ, phải trùng {@code borrowerId} (claim {@code user_id}) của request nộp hồ sơ
 * @param ekycStatus {@code PENDING}, {@code VERIFIED}, {@code FAILED} hoặc {@code MANUAL_REVIEW}
 * @param age        tuổi tròn theo ngày Việt Nam, {@code null} khi chưa có ngày sinh từ eKYC
 */
public record UserKycSummary(
        String userId,
        String ekycStatus,
        boolean documentVerified,
        Instant ekycCompletedAt,
        Integer age
) {
}
