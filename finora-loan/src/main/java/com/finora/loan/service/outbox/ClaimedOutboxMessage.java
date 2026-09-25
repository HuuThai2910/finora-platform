package com.finora.loan.service.outbox;

import java.util.UUID;

/** Claim token ngăn một relay cũ xác nhận nhầm event đã được instance khác tiếp quản. */
public record ClaimedOutboxMessage(
        Long databaseId,
        UUID claimToken,
        OutboxMessage message
) {
}
