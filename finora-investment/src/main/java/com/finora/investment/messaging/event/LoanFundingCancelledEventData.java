package com.finora.investment.messaging.event;

import java.time.Instant;

/**
 * Payload `LoanFundingCancelled.v1` từ Loan: đợt gọi vốn bị dừng trước giải ngân.
 *
 * <p>Contract đóng thì đối chiếu listing/round/allocation. Borrower rút lúc listing còn OPEN thì
 * listing/allocation có thể null và tra theo applicationNumber + fundingRound.</p>
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
