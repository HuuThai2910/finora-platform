package com.finora.investment.dto.response;

import java.time.Instant;

/** Một dòng trong danh sách sổ lệnh: khoản vay gốc và giá tốt nhất hai phía. */
public record OrderBookSummaryResponse(
        Long listingId,
        Long loanId,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        String noteDenomination,
        boolean defaulted,
        String bestBidPercent,
        String bestAskPercent,
        String lastTradePercent,
        Instant lastTradeAt,
        /** Mã hiển thị {@code HS-...}/{@code HĐ-...} của khoản vay; null với khoản niêm yết trước khi Loan có mã. */
        String applicationCode,
        String contractCode,
        /** Mục đích vay (nhãn) để app gọi tên khoản vay thay cho mã số; {@code purposeCode} để chọn biểu tượng. */
        String purpose,
        String purposeCode
) {
}
