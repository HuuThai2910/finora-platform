package com.finora.loan.domain.contract.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Danh tính bên vay in trên hợp đồng, chụp từ finora-user lúc lập hợp đồng.
 *
 * <p>Chỉ có họ tên và số CCCD đã che 4 số cuối: User không bao giờ trả số đầy đủ cho Loan ({@code LEGAL-DATA-01}).
 * Hai trường được để {@code null} khi người vay chưa cập nhật hồ sơ; văn bản khi đó ghi "Chưa cập nhật hồ sơ" chứ không
 * tự điền tên.</p>
 *
 * @param fullName       họ tên theo hồ sơ User, {@code null} khi chưa có
 * @param maskedIdNumber số CCCD đã che, {@code null} khi chưa có CCCD
 * @param capturedAt     thời điểm Loan đọc danh tính từ User
 */
public record BorrowerIdentity(String fullName, String maskedIdNumber, Instant capturedAt) {

    public BorrowerIdentity {
        fullName = blankToNull(fullName);
        maskedIdNumber = blankToNull(maskedIdNumber);
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
