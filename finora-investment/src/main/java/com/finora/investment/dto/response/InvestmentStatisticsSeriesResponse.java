package com.finora.investment.dto.response;

import com.finora.common.statistics.StatisticsBucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Chuỗi vốn góp, khớp lệnh chợ Notes và Auto-Invest theo cột thời gian (STATS-001).
 *
 * <p>{@code from}/{@code to} là khoảng đã nới cho tròn cột; {@code points} luôn đủ mọi cột kể cả cột 0.
 * Tiền là {@link BigDecimal} scale 2 ra JSON number theo hợp đồng STATS-001.</p>
 *
 * @param timezone múi giờ dùng để cắt cột, luôn {@code Asia/Ho_Chi_Minh}
 */
public record InvestmentStatisticsSeriesResponse(
        LocalDate from,
        LocalDate to,
        StatisticsBucket bucket,
        String timezone,
        List<Point> points
) {

    /**
     * Số liệu của một cột.
     *
     * @param committedAmount      tổng vốn cam kết tạo trong cột, bỏ phần đã huỷ
     * @param commitments          số phần vốn cam kết tạo trong cột, bỏ phần đã huỷ
     * @param listingsFullyFunded  số listing đủ vốn trong cột (theo {@code fully_funded_at})
     * @param trades               số lần khớp trên chợ Notes, bỏ lần Payment từ chối thanh toán
     * @param tradedAmount         tổng tiền các lần khớp đó
     * @param platformFee          tổng phí nền tảng các lần khớp đó
     * @param averagePricePermille giá khớp bình quân theo khối lượng (phần nghìn dư nợ gốc), làm tròn
     *                             HALF_UP; {@code null} khi cột không có lần khớp
     * @param tradedQuantity       tổng số Note đã khớp (trọng số để web tự gộp giá nhiều cột)
     * @param performingTrades     số lần khớp của Note chưa nợ xấu lúc khớp ({@code defaulted = false})
     * @param performingQuantity   tổng số Note của các lần khớp đó
     * @param performingAveragePricePermille giá bình quân theo khối lượng chỉ của Note chưa nợ xấu, HALF_UP;
     *                             {@code null} khi cột không có lần khớp nào như vậy. Web tính trung bình
     *                             trượt 7 ngày bằng Σ(giá × khối lượng) / Σ khối lượng từ hai trường này
     * @param autoInvestPlaced     số lần Auto-Invest đặt được lệnh ({@code MATCHED})
     * @param autoInvestSkipped    số lần Auto-Invest bỏ qua ({@code SKIPPED})
     */
    public record Point(
            LocalDate bucketStart,
            BigDecimal committedAmount,
            long commitments,
            long listingsFullyFunded,
            long trades,
            BigDecimal tradedAmount,
            BigDecimal platformFee,
            Integer averagePricePermille,
            long tradedQuantity,
            long performingTrades,
            long performingQuantity,
            Integer performingAveragePricePermille,
            long autoInvestPlaced,
            long autoInvestSkipped
    ) {
    }
}
