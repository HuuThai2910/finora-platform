package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một nhà đầu tư đã góp vốn vào khoản vay, nhìn từ phía quản trị.
 *
 * <p>Tách khỏi {@code CommitmentResponse} vì hai góc nhìn khác nhau: nhà đầu tư xem
 * phần vốn của chính mình nên không cần biết mình là ai, còn quản trị cần
 * {@code investorId} để biết ai đã góp. Trường này chỉ lộ ra ở endpoint quản trị.</p>
 *
 * <p>Không kèm tên hay thông tin liên hệ: hồ sơ người dùng thuộc finora-user, và niêm yết
 * trên sàn cố ý không mang dữ liệu cá nhân (F03 bước 4).</p>
 *
 * @param sharePercent phần trăm vốn của nhà đầu tư này trong tổng mục tiêu gọi vốn
 */
public record ListingInvestorResponse(
        Long commitmentId,
        String investorId,
        String amount,
        Integer noteCount,
        BigDecimal sharePercent,
        String status,
        Instant createdAt
) {
}
