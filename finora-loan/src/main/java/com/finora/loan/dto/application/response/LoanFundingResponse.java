package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.LoanFundingStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Projection read-only để client không suy đoán Contract từ trạng thái APPROVED. */
public record LoanFundingResponse(
        LoanFundingStatus status,
        Integer fundingRound,
        Integer listingVersion,
        Instant requestedAt,
        Long investmentListingId,
        BigDecimal fundedAmount,
        Instant fullyFundedAt,
        /** Có giá trị khi {@code status = EXPIRED}: đợt gọi vốn hết hạn mà chưa đủ vốn. */
        Instant expiredAt,
        /** Có giá trị khi {@code status = CANCELLED}: đã đủ vốn nhưng không giải ngân, tiền giữ được trả lại. */
        Instant cancelledAt,
        /** {@code CONTRACT_DECLINED}, {@code CONTRACT_EXPIRED} hoặc {@code DISBURSEMENT_FAILED}. */
        String cancelReason
) {
}
