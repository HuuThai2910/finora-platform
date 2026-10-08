package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Ảnh chụp hiện tại của sàn gọi vốn và Notes cho trang thống kê quản trị (STATS-001).
 *
 * <p>Khác các response khác của module (tiền dạng chuỗi), tiền ở đây là {@link BigDecimal} scale 2 ra
 * JSON number theo hợp đồng STATS-001 dùng chung với Loan và User: web chỉ vẽ biểu đồ, không tính tiếp
 * trên các số này. Chỉ có số đếm và tổng tiền, không có mã nhà đầu tư.</p>
 *
 * @param asOf thời điểm đọc số liệu
 */
public record InvestmentStatisticsSummaryResponse(
        Instant asOf,
        Listings listings,
        OpenFunding openFunding,
        Notes notes,
        AutoInvest autoInvest
) {

    /** @param byStatus số listing theo trạng thái, đủ mọi giá trị {@code ListingStatus} kể cả 0 */
    public record Listings(Map<String, Long> byStatus) {
    }

    /**
     * Các listing đang mở gọi vốn ({@code OPEN}).
     *
     * @param fillRatePercent vốn đã gom / mục tiêu × 100, 2 chữ số; {@code null} khi không có listing mở
     */
    public record OpenFunding(
            long listings,
            BigDecimal targetAmount,
            BigDecimal committedAmount,
            BigDecimal fillRatePercent
    ) {
    }

    /**
     * @param byStatus                   số Note theo trạng thái, đủ mọi giá trị {@code NoteStatus} kể cả 0
     * @param activeOutstandingPrincipal tổng dư nợ gốc còn lại của Note đang {@code ACTIVE}
     */
    public record Notes(Map<String, Long> byStatus, BigDecimal activeOutstandingPrincipal) {
    }

    /** @param activeConfigs số cấu hình Auto-Invest đang bật */
    public record AutoInvest(long activeConfigs) {
    }
}
