package com.finora.payment.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code RepaymentCollected.v1} (P5, W3F, D77) trên {@code finora.payment.repayment-collected}: Payment đã trừ ví người vay
 * cho một kỳ trả trước ngày đến hạn và giữ tiền ở clearing tới {@code dueDate} (D16); Fineract chưa biết khoản này nên
 * Notification dùng event để không nhắc kỳ đó và báo người vay đã nhận tiền. Chỉ phát khi khoản trả được hẹn ghi core
 * ({@code postOn}); khoản ghi Fineract ngay đã có {@code RepaymentDistributed} theo sau. Không mang PII.
 *
 * @param borrowerId    ID logic của người vay (claim {@code user_id})
 * @param repaymentType luôn {@code SCHEDULED} ở bản hiện tại
 * @param amount        số tiền đã trừ ví, chuỗi decimal VND hai chữ số thập phân
 * @param dueDate       ngày đến hạn của kỳ, cũng là ngày Payment ghi Fineract ({@code postOn})
 * @param collectedAt   thời điểm trừ ví (UTC)
 */
public record RepaymentCollectedEventData(UUID repaymentId, Long loanApplicationId, String borrowerId,
        String repaymentType, String amount, String currency, LocalDate dueDate, Instant collectedAt) {
}
