package com.finora.loan.messaging.event;

import java.time.Instant;

/**
 * Payload `LoanFundingCancelled.v1`: đợt gọi vốn bị dừng trước giải ngân.
 *
 * <p>Với allocation đã khóa, event mang listing/allocation để Investment đối chiếu. Khi borrower rút lúc
 * listing còn OPEN, các field listing/allocation có thể null và consumer tra bằng
 * {@code applicationNumber + fundingRound}. Không mang PII, lý do tự do hay lỗi provider.</p>
 *
 * @param borrowerId ID logic của người vay (claim {@code user_id}); field optional thêm sau (P2, W3F) để Notification báo
 *                   người vay, consumer cũ bỏ qua
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
        Instant cancelledAt,
        String borrowerId
) {
}
