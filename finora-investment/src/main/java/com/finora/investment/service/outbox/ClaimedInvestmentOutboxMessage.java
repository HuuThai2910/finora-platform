package com.finora.investment.service.outbox;

import java.util.UUID;

/**
 * Event outbox vừa được nhận để gửi, kèm id dòng và claim token dùng khi ghi kết quả.
 */
public record ClaimedInvestmentOutboxMessage(
        Long databaseId,
        UUID claimToken,
        InvestmentOutboxMessage message
) {
}
