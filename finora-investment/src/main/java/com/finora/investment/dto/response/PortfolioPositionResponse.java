package com.finora.investment.dto.response;

import java.math.BigDecimal;

/**
 * Một vị thế trong danh mục — gộp toàn bộ Note của cùng một khoản vay.
 *
 * <p>{@code status} là trạng thái Note của vị thế: {@code "ACTIVE"} khi khoản vay đang trả bình
 * thường, {@code "DEFAULTED"} khi khoản vay ở nhóm nợ 3–5 (nợ xấu). App và Trợ lý FINORA dựa vào
 * trường này để gắn nhãn "Nợ xấu"; {@code servicingStatus} là chuyện khác — trạng thái vận hành
 * khoản vay do Loan gửi sang (đang trả, đã tất toán...).</p>
 */
public record PortfolioPositionResponse(
        Long loanId,
        Long listingId,
        String purpose,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        int noteCount,
        String principalAmount,
        String outstandingPrincipal,
        String principalRepaid,
        String interestReceived,
        BigDecimal sharePercent,
        String status,
        int daysPastDue,
        int debtGroup,
        String overdueAmount,
        String servicingStatus,
        java.time.LocalDate maturityDate,
        java.time.Instant riskDataAsOf
) {
}
