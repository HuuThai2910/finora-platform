package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một khoản vay trên sàn.
 *
 * <p>Không chứa PII của người vay: chỉ khu vực, mục đích vay và hạng tín dụng, đúng như
 * dữ liệu market tối thiểu mà finora-loan được phép công bố (F03).</p>
 *
 * <p>Tiền truyền dạng chuỗi decimal theo 04-conventions-contracts.md để client không mất
 * chính xác khi parse thành số thực.</p>
 */
public record MarketListingResponse(
        Long listingId,
        Long loanId,
        String purpose,
        String region,
        String creditGrade,
        Integer creditScore,
        String targetAmount,
        String committedAmount,
        String remainingAmount,
        BigDecimal fundedPercent,
        String annualInterestRate,
        Integer termMonths,
        String repaymentMethod,
        String noteDenomination,
        String minInvestmentAmount,
        String status,
        Instant fundingClosesAt
) {
}
