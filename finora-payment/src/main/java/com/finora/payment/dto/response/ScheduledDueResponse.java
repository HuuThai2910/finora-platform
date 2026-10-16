package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PaymentRepaymentType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Nghĩa vụ phải trả ngay lúc này theo Fineract — đúng con số mà {@code POST /api/v1/repayments} sẽ kiểm.
 *
 * <p>Client hiển thị và gửi lại nguyên {@code amount}; không tự cộng kỳ hay phần quá hạn từ projection, vì projection
 * có thể cũ và Fineract làm tròn kỳ theo bội số tiền tệ (VND làm tròn 1.000).</p>
 *
 * @param amount        số tiền cần trả, VND, tối đa 2 chữ số thập phân
 * @param overdueAmount phần đã quá hạn trong {@code amount}; lớn hơn 0 thì lệnh là khắc phục quá hạn
 * @param repaymentType loại lệnh sẽ được tạo nếu trả đúng số tiền này
 * @param businessDate  ngày nghiệp vụ Việt Nam của báo giá; lệnh trả nợ phải được gửi trong cùng ngày
 */
public record ScheduledDueResponse(
        Long loanApplicationId,
        BigDecimal amount,
        BigDecimal overdueAmount,
        String currency,
        PaymentRepaymentType repaymentType,
        LocalDate businessDate
) {
}
