package com.finora.payment.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Nghĩa vụ Fineract yêu cầu cho lượt trả kỳ kế tiếp.
 *
 * @param nextDueDate ngày đến hạn của kỳ đầu tiên còn dư nợ, tức kỳ mà {@code amount} thanh toán; {@code null} khi core
 *                    không trả lịch. Payment dùng ngày này để giữ khoản trả sớm của khoản vay core V4 tới đúng ngày (D16).
 */
public record ScheduledRepaymentCoreQuote(BigDecimal amount, BigDecimal overdueAmount, LocalDate nextDueDate) {

    public ScheduledRepaymentCoreQuote(BigDecimal amount, BigDecimal overdueAmount) {
        this(amount, overdueAmount, null);
    }
}
