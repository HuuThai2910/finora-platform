package com.finora.investment.messaging.event;

import java.time.Instant;

/**
 * {@code LoanFundingExpired.v1}: đợt gọi vốn hết hạn mà chưa đủ vốn. Investment đã huỷ mọi phần
 * vốn và đang nhả tiền giữ về ví nhà đầu tư; Loan chỉ cần biết sự việc để đóng bước chờ gọi vốn
 * của hồ sơ. Không mang PII.
 *
 * @param committedAmount số đã gom được trước khi hết hạn, để hiển thị — không còn hiệu lực
 * @param reason          hiện chỉ có {@code FUNDING_WINDOW_EXPIRED}
 */
public record LoanFundingExpiredEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        Integer fundingRound,
        String targetAmount,
        String committedAmount,
        String currency,
        Instant fundingOpenedAt,
        Instant fundingClosedAt,
        String reason
) {
}
