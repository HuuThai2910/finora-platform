package com.finora.loan.messaging.event;

import java.time.Instant;

/**
 * Payload `LoanFundingCancelled.v1`: đợt gọi vốn đã khóa allocation nhưng khoản vay không được giải ngân.
 *
 * <p>Event chỉ mang business ID và snapshot allocation để Investment đối chiếu đúng listing trước khi hủy
 * phần vốn; không mang PII, lý do tự do của người vay hay chi tiết lỗi provider. {@code reasonCode} là một
 * trong {@code CONTRACT_DECLINED}, {@code CONTRACT_EXPIRED}, {@code DISBURSEMENT_FAILED}.</p>
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
