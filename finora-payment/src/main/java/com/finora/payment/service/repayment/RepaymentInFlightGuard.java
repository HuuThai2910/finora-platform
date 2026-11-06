package com.finora.payment.service.repayment;

import com.finora.payment.domain.repayment.entity.PaymentRepayment;

import java.time.Instant;
import java.util.Optional;

/**
 * Mỗi khoản vay chỉ có tối đa một khoản đã thu mà Fineract chưa ghi xong.
 *
 * <p>Fineract chỉ biết khoản tiền sau khi đã ghi. Cho trả thêm hoặc lập báo giá trong lúc còn khoản chờ thì người vay
 * có thể trả hai lần cùng một kỳ, hoặc nhận báo giá tính trên dư nợ cũ. Lệnh ghi tiền gọi guard trong transaction đã
 * khóa dòng {@code payment_loan_accounts} để hai request đồng thời không cùng vượt qua kiểm tra.</p>
 */
public interface RepaymentInFlightGuard {

    /** Khoản đã thu cũ nhất của khoản vay mà Fineract chưa ghi xong, nếu có. */
    Optional<PaymentRepayment> oldestInFlight(Long loanApplicationId);

    /** Trả kỳ: kỳ đã thanh toán đang chờ ngày ghi nhận, hoặc lệnh trước chưa ghi xong core. */
    void requireNoneBeforeScheduledPayment(Long loanApplicationId);

    /** Báo giá trả trước/tất toán phải tính trên dư nợ đã gồm mọi khoản người vay đã nộp. */
    void requireNoneBeforeQuote(Long loanApplicationId);

    /** Thực hiện báo giá: từ lúc lập báo giá không được có khoản nào khác làm đổi dư nợ core. */
    void requireUnchangedSince(Long loanApplicationId, Instant quotedAt, String code);
}
