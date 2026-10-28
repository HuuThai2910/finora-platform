package com.finora.loan.integration.fineract.client;

import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public interface CoreLoanBookingGateway {
    CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand command);

    record CoreLoanBookingCommand(Long loanApplicationId, String contractNumber, String borrowerId,
            Long productId, BigDecimal principal, Integer termMonths, BigDecimal annualRate,
            RepaymentMethod repaymentMethod, String coreConfigVersion, LocalDate disbursementDate,
            String paymentReference) {}
    /**
     * Kết quả ghi khoản vay vào core. Ba ngày/số tiền dưới đây đọc từ cùng một lần đọc khoản vay của Fineract (B34, P3);
     * {@code null} khi core không trả (gateway giả lập). FINORA không tự tính lịch.
     *
     * @param maturityDate  ngày đáo hạn Fineract tính theo ngày giải ngân thực tế ({@code timeline.expectedMaturityDate})
     * @param nextDueDate   ngày đến hạn của kỳ đầu tiên còn phải trả trong lịch Fineract
     * @param nextDueAmount số tiền của kỳ đó, luôn hai chữ số thập phân; số lẻ hơn bị bỏ trống thay vì làm tròn
     */
    record CoreLoanBookingResult(Long fineractLoanId, LocalDate maturityDate, LocalDate nextDueDate,
            BigDecimal nextDueAmount) {
        public CoreLoanBookingResult {
            nextDueAmount = twoDecimalsOrNull(nextDueAmount);
        }

        public CoreLoanBookingResult(Long fineractLoanId) {
            this(fineractLoanId, null, null, null);
        }

        public CoreLoanBookingResult(Long fineractLoanId, LocalDate maturityDate) {
            this(fineractLoanId, maturityDate, null, null);
        }

        /** Field phụ cho thông báo không được làm hỏng lần ghi core: không biểu diễn đúng hai số lẻ thì bỏ trống. */
        private static BigDecimal twoDecimalsOrNull(BigDecimal value) {
            if (value == null) return null;
            try {
                return value.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException notExact) {
                return null;
            }
        }
    }
}
