package com.finora.loan.dto.statistics.response;

import com.finora.common.statistics.StatisticsBucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Chuỗi thống kê Loan theo cột thời gian giờ Việt Nam (STATS-001 §2.2).
 * {@code points} đủ mọi cột kể cả cột 0; {@code productPoints} chỉ gồm dòng khác 0 để web ghép theo productId.
 */
public record LoanStatisticsSeriesResponse(
        LocalDate from,
        LocalDate to,
        StatisticsBucket bucket,
        String timezone,
        List<Point> points,
        List<ProductPoint> productPoints,
        Funnel funnel
) {

    public record Point(
            LocalDate bucketStart,
            long applicationsSubmitted,
            // Tổng số tiền xin vay của hồ sơ nộp trong cột (theo submitted_at), scale 2; 0.00 khi không có hồ sơ.
            BigDecimal applicationsSubmittedAmount,
            long applicationsApproved,
            long applicationsRejected,
            long loansDisbursed,
            BigDecimal disbursedAmount
    ) {}

    public record ProductPoint(LocalDate bucketStart, Long productId, long applicationsSubmitted,
            BigDecimal applicationsSubmittedAmount, BigDecimal disbursedAmount) {}

    /** Phễu cohort của hồ sơ nộp trong khoảng; mỗi bước đếm theo trạng thái hiện tại của hồ sơ. */
    public record Funnel(
            long submitted,
            long scored,
            long approved,
            long termsAccepted,
            long fundingRequested,
            long fullyFunded,
            long disbursed
    ) {}
}
