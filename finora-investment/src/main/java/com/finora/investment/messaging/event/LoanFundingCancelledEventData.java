package com.finora.investment.messaging.event;

import java.time.Instant;

/**
 * Payload `LoanFundingCancelled.v1` từ Loan: allocation đã khóa nhưng khoản vay không được giải ngân.
 *
 * <p>Investment đối chiếu listing/round/allocation trước khi hủy phần vốn; không có PII hay lý do tự do.</p>
 */
public record LoanFundingCancelledEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        Integer fundingRound,
        String contractNumber,
        Long allocationVersion,
        String allocationHash,
        String reasonCode,
        Instant cancelledAt
) {
}
