package com.finora.loan.dto.statistics.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Ảnh chụp hiện tại của danh mục cho vay cho trang quản trị (STATS-001 §2.2).
 * Chỉ chứa số đếm và tổng tiền, không có PII. Map trạng thái luôn đủ mọi giá trị enum (kể cả 0)
 * theo thứ tự khai báo để web vẽ ổn định.
 */
public record LoanStatisticsSummaryResponse(
        Instant asOf,
        Applications applications,
        Portfolio portfolio,
        Collections collections,
        StatusBreakdown reschedules,
        StatusBreakdown reconciliationIncidents,
        CreditScores creditScores
) {

    public record Applications(long total, Map<String, Long> byStatus, Map<String, Long> byFundingStatus) {}

    /**
     * Dư nợ lấy từ {@code loan_servicing_projections}; {@code nplRatioPercent} là null khi dư nợ gốc bằng 0
     * vì tỷ lệ trên mẫu số 0 không có nghĩa (khác với "0% nợ xấu").
     */
    public record Portfolio(
            Map<String, Long> loansByStatus,
            long outstandingLoans,
            BigDecimal principalOutstanding,
            BigDecimal totalOutstanding,
            BigDecimal overdueAmount,
            BigDecimal nplPrincipalOutstanding,
            BigDecimal nplRatioPercent,
            long staleProjections,
            List<DebtGroupBreakdown> byDebtGroup,
            List<CreditGradeBreakdown> byCreditGrade,
            List<ProductBreakdown> byProduct
    ) {}

    public record DebtGroupBreakdown(int debtGroup, long loans, BigDecimal principalOutstanding,
            BigDecimal overdueAmount) {}

    public record CreditGradeBreakdown(String grade, long loans, BigDecimal principalOutstanding) {}

    public record ProductBreakdown(
            Long productId,
            String productCode,
            String productName,
            long applications,
            long outstandingLoans,
            BigDecimal principalOutstanding,
            BigDecimal nplPrincipalOutstanding,
            BigDecimal nplRatioPercent
    ) {}

    public record Collections(long openCases, Map<String, Long> openByStage, Map<String, Long> byStatus) {}

    public record StatusBreakdown(Map<String, Long> byStatus) {}

    public record CreditScores(long assessed, Map<String, Long> byGrade, List<ScoreBucket> histogram) {}

    /** Cột điểm [from, to); riêng cột cuối gồm cả {@code to} = 100. */
    public record ScoreBucket(int from, int to, long count) {}
}
