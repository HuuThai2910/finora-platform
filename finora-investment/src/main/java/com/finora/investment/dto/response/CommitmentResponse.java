package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phần vốn đã cam kết của nhà đầu tư trong một khoản vay.
 *
 * @param noteCount   số Note sẽ được phát hành khi khoản vay giải ngân
 * @param sharePercent tỷ lệ sở hữu trên tổng khoản vay, dùng để phân bổ tiền thu về
 */
public record CommitmentResponse(
        Long commitmentId,
        Long listingId,
        Long loanId,
        String amount,
        Integer noteCount,
        String noteDenomination,
        BigDecimal sharePercent,
        String status,
        Instant createdAt
) {
}
