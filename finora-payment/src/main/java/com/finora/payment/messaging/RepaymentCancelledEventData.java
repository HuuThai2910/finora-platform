package com.finora.payment.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code RepaymentCancelled.v1} (P5, W3F, D77) trên {@code finora.payment.repayment-cancelled}: người vay đã hủy khoản trả
 * kỳ trước hạn đang giữ ({@code RepaymentCollected}) và tiền đã hoàn về ví trong cùng transaction. Kỳ đó lại cần được nhắc.
 * Không mang PII.
 *
 * @param dueDate     ngày đến hạn của kỳ đã hủy khoản giữ ({@code postOn} của khoản trả)
 * @param cancelledAt thời điểm hủy (UTC)
 */
public record RepaymentCancelledEventData(UUID repaymentId, Long loanApplicationId, String borrowerId,
        LocalDate dueDate, Instant cancelledAt) {
}
