package com.finora.payment.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Nghĩa vụ Fineract yêu cầu cho lượt trả kỳ kế tiếp.
 *
 * @param amount        nghĩa vụ của kỳ chưa trả cũ nhất theo template repayment của Fineract. Khi quá hạn nhiều kỳ,
 *                      template chỉ trả kỳ cũ nhất, nên đây không phải số khắc phục quá hạn (B31)
 * @param overdueAmount toàn bộ phần quá hạn ({@code summary.totalOverdue}, Fineract cập nhật qua job Update Loan Arrears
 *                      Ageing cuối ngày)
 * @param nextDueDate   ngày đến hạn của kỳ đầu tiên còn dư nợ, tức kỳ mà {@code amount} thanh toán; {@code null} khi core
 *                      không trả lịch. Payment dùng ngày này để giữ khoản trả sớm của khoản vay core V4 tới đúng ngày (D16).
 */
public record ScheduledRepaymentCoreQuote(BigDecimal amount, BigDecimal overdueAmount, LocalDate nextDueDate) {

    public ScheduledRepaymentCoreQuote(BigDecimal amount, BigDecimal overdueAmount) {
        this(amount, overdueAmount, null);
    }

    /**
     * Số tiền mặc định phải trả. Có quá hạn thì là toàn bộ phần quá hạn để khoản vay trở lại bình thường
     * (LOAN-SERVICING-POLICY §3.4), không nhỏ hơn kỳ cũ nhất; không quá hạn thì là kỳ đến hạn gần nhất.
     */
    public BigDecimal payableAmount() {
        return overdueAmount.signum() > 0 ? amount.max(overdueAmount) : amount;
    }

    /**
     * Trả bớt nợ quá hạn (D26): chỉ kỳ quá hạn cũ nhất, khi quá hạn nhiều kỳ và số này nhỏ hơn số khắc phục toàn bộ.
     * Khoản vay vẫn quá hạn sau lượt trả này. {@code null} khi không áp dụng.
     */
    public BigDecimal partialOverdueAmount() {
        if (overdueAmount.signum() <= 0 || amount.signum() <= 0) return null;
        return amount.compareTo(payableAmount()) < 0 ? amount : null;
    }

    /** Chỉ nhận đúng một trong hai con số Fineract đã báo; mọi số khác đều bị từ chối. */
    public boolean accepts(BigDecimal value) {
        BigDecimal partial = partialOverdueAmount();
        return value.compareTo(payableAmount()) == 0 || (partial != null && value.compareTo(partial) == 0);
    }
}
