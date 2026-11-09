package com.finora.loan.integration.profile.contract;

/**
 * Response {@code GET /api/v1/internal/users/{userId}/identity} của finora-user. User chỉ trả số CCCD đã che 4 số cuối,
 * Loan không bao giờ thấy số đầy đủ ({@code LEGAL-DATA-01}).
 *
 * @param userId         giá trị Loan đã gửi trong path
 * @param fullName       họ tên theo hồ sơ, {@code null} khi người dùng chưa cập nhật
 * @param maskedIdNumber số CCCD đã che, {@code null} khi chưa có CCCD
 */
public record UserIdentitySummary(String userId, String fullName, String maskedIdNumber) {
}
