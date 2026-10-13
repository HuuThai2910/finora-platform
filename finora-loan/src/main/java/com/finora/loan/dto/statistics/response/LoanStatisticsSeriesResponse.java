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
        Funnel funnel,
        PortfolioHistory portfolioHistory
) {

    public record Point(
            LocalDate bucketStart,
            long applicationsSubmitted,
            // Tổng số tiền xin vay của hồ sơ nộp trong cột (theo submitted_at), scale 2; 0.00 khi không có hồ sơ.
            BigDecimal applicationsSubmittedAmount,
            long applicationsApproved,
            long applicationsRejected,
            long loansDisbursed,
            BigDecimal disbursedAmount,
            // Dư nợ cuối cột tính lại từ lịch sử Fineract; null với cột chưa tới hoặc khi không đọc được khoản nào.
            PortfolioPosition portfolio
    ) {}

    /**
     * Danh mục tại cuối cột: ngày cuối của cột, hoặc hôm nay với cột đang chạy. Chỉ gồm khoản còn dư nợ gốc
     * tại ngày đó; nhóm nợ theo số ngày quá hạn của ngày đó.
     */
    public record PortfolioPosition(
            LocalDate asOf,
            long loans,
            BigDecimal principalOutstanding,
            BigDecimal nplPrincipalOutstanding,
            // Phần trăm, 2 chữ số; null khi dư nợ gốc bằng 0.
            BigDecimal nplRatioPercent,
            // Đủ 5 nhóm, nhóm 1 trước.
            List<DebtGroupPosition> debtGroups
    ) {}

    public record DebtGroupPosition(int debtGroup, long loans, BigDecimal principalOutstanding) {}

    /**
     * Nguồn của {@link PortfolioPosition}: số khoản vay đã giải ngân trong kỳ cần đọc lịch sử, và số khoản
     * không đọc được từ Fineract (bị bỏ khỏi dư nợ theo ngày, nên dư nợ theo ngày có thể thấp hơn số hiện tại).
     */
    public record PortfolioHistory(long loans, long unavailableLoans) {}

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
