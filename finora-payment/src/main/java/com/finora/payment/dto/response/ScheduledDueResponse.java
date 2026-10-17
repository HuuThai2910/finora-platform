package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.repayment.PaymentRepaymentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Nghĩa vụ phải trả ngay lúc này theo Fineract — đúng con số mà {@code POST /api/v1/repayments} sẽ kiểm.
 *
 * <p>Client hiển thị và gửi lại nguyên {@code amount}; không tự cộng kỳ hay phần quá hạn từ projection, vì projection
 * có thể cũ và Fineract làm tròn kỳ theo bội số tiền tệ (VND làm tròn 1.000).</p>
 *
 * @param amount        số tiền cần trả, VND, tối đa 2 chữ số thập phân. Có quá hạn thì là toàn bộ phần quá hạn (khắc
 *                      phục, B31), không chỉ kỳ cũ nhất
 * @param overdueAmount phần đã quá hạn trong {@code amount}; lớn hơn 0 thì lệnh là khắc phục quá hạn
 * @param partialOverdueAmount khi quá hạn nhiều kỳ: số tiền của riêng kỳ quá hạn cũ nhất, người vay được chọn trả bớt
 *                      thay cho {@code amount} (D26); khoản vay vẫn quá hạn sau đó. {@code null} khi không áp dụng
 * @param repaymentType loại lệnh sẽ được tạo nếu trả đúng số tiền này
 * @param businessDate  ngày nghiệp vụ Việt Nam của báo giá; lệnh trả nợ phải được gửi trong cùng ngày
 * @param dueDate       ngày đến hạn của kỳ mà {@code amount} thanh toán; {@code null} nếu core không trả lịch
 * @param postOn        nếu trả ngay bây giờ: ngày khoản tiền được ghi vào sổ nợ (ngày đến hạn, D16); {@code null} là
 *                      ghi ngay
 * @param pending       khoản đã thu nhưng chưa ghi xong vào sổ nợ; khác {@code null} thì chưa trả thêm được
 */
public record ScheduledDueResponse(
        Long loanApplicationId,
        BigDecimal amount,
        BigDecimal overdueAmount,
        BigDecimal partialOverdueAmount,
        String currency,
        PaymentRepaymentType repaymentType,
        LocalDate businessDate,
        LocalDate dueDate,
        LocalDate postOn,
        PendingRepayment pending
) {

    /**
     * @param postOn ngày ghi nhận đã hẹn; khác {@code null} với khoản trả kỳ trước hạn, hủy được trước ngày này
     */
    public record PendingRepayment(UUID repaymentId, BigDecimal amount, PaymentRepaymentStatus status,
            LocalDate postOn, boolean cancellable) {

        public static PendingRepayment from(PaymentRepayment value, LocalDate today) {
            return new PendingRepayment(value.getRepaymentId(), value.getAmount(), value.getStatus(),
                    value.getPostOn(), value.awaitingDueDate(today));
        }
    }
}
