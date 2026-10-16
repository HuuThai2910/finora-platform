package com.finora.user.dto.response;

import com.finora.common.enums.user.EkycStatus;
import java.time.Instant;

/**
 * Tóm tắt eKYC của chính người dùng cho service khác (W2-KYC): Loan chuyển tiếp token của người vay khi nộp hồ sơ để
 * biết người vay đã xác minh danh tính chưa và tuổi bao nhiêu.
 *
 * <p>Cố ý không có họ tên, ngày sinh, CCCD, số điện thoại, email hay địa chỉ: Loan chỉ cần trạng thái và tuổi để xét điều
 * kiện, nên không được nhận dữ liệu định danh (tối thiểu hóa dữ liệu, {@code LEGAL-DATA-01}).</p>
 *
 * @param userId           ID hồ sơ nội bộ, cùng giá trị claim {@code user_id} mà service khác dùng làm định danh
 * @param ekycStatus       trạng thái eKYC hiện tại trong DB User (claim trong token có thể cũ tới lần refresh)
 * @param documentVerified CCCD đã được người dùng xác nhận sau OCR
 * @param ekycCompletedAt  thời điểm hoàn tất eKYC, {@code null} khi chưa xong
 * @param age              tuổi tròn theo ngày Việt Nam, {@code null} khi chưa có ngày sinh từ eKYC
 */
public record KycSummaryResponse(
        String userId,
        EkycStatus ekycStatus,
        boolean documentVerified,
        Instant ekycCompletedAt,
        Integer age
) {
}
