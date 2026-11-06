package com.finora.payment.service.repayment.impl;

import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.repayment.entity.PaymentRepayment;
import com.finora.payment.domain.repayment.enums.PaymentRepaymentStatus;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import com.finora.payment.service.repayment.RepaymentInFlightGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Mỗi khoản vay chỉ có tối đa một khoản đã thu mà Fineract chưa ghi xong.
 *
 * <p>Fineract chỉ biết khoản tiền sau khi đã ghi. Cho trả thêm hoặc lập báo giá trong lúc còn khoản chờ thì người vay có
 * thể trả hai lần cùng một kỳ, hoặc nhận báo giá tính trên dư nợ cũ. Lệnh ghi tiền gọi guard trong transaction đã khóa
 * dòng {@code payment_loan_accounts} để hai request đồng thời không cùng vượt qua kiểm tra.</p>
 */
@Component
@RequiredArgsConstructor
public class RepaymentInFlightGuardImpl implements RepaymentInFlightGuard {
    static final List<PaymentRepaymentStatus> IN_FLIGHT = List.of(PaymentRepaymentStatus.COLLECTED,
            PaymentRepaymentStatus.CORE_POSTING, PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
    /** Trạng thái không làm đổi dư nợ core: core từ chối (đã hoàn tiền) hoặc người vay đã hủy khoản đang giữ. */
    private static final List<PaymentRepaymentStatus> NO_CORE_EFFECT = List.of(PaymentRepaymentStatus.FAILED,
            PaymentRepaymentStatus.CANCELLED);
    private static final DateTimeFormatter VIETNAM_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PaymentRepaymentRepository paymentRepaymentRepository;
    private final Clock clock;

    @Override
    public Optional<PaymentRepayment> oldestInFlight(Long loanApplicationId) {
        return paymentRepaymentRepository.findByLoanAndStatuses(loanApplicationId, IN_FLIGHT).stream().findFirst();
    }

    @Override
    public void requireNoneBeforeScheduledPayment(Long loanApplicationId) {
        oldestInFlight(loanApplicationId).ifPresent(pending -> {
            if (pending.awaitingDueDate(LocalDate.now(clock))) {
                throw new BusinessException(HttpStatus.CONFLICT, "INSTALLMENT_ALREADY_PAID_PENDING",
                        "Kỳ đến hạn " + VIETNAM_DATE.format(pending.getPostOn())
                                + " đã được thanh toán; khoản tiền được ghi nhận vào sổ nợ đúng ngày đến hạn");
            }
            throw inProgress();
        });
    }

    @Override
    public void requireNoneBeforeQuote(Long loanApplicationId) {
        oldestInFlight(loanApplicationId).ifPresent(pending -> {
            if (pending.awaitingDueDate(LocalDate.now(clock))) {
                throw new BusinessException(HttpStatus.CONFLICT, "INSTALLMENT_PENDING_POSTING",
                        "Kỳ đến hạn " + VIETNAM_DATE.format(pending.getPostOn()) + " đã thanh toán và đang chờ ghi nhận "
                                + "đúng ngày đến hạn. Hủy khoản chờ ghi nhận (tiền hoàn về ví) để lập báo giá trả trước "
                                + "hoặc tất toán theo dư nợ hiện tại");
            }
            throw inProgress();
        });
    }

    @Override
    public void requireUnchangedSince(Long loanApplicationId, Instant quotedAt, String code) {
        if (paymentRepaymentRepository.existsCreatedAfter(loanApplicationId, quotedAt, NO_CORE_EFFECT)) {
            throw new BusinessException(HttpStatus.CONFLICT, code,
                    "Khoản vay đã có giao dịch mới sau khi lập báo giá; vui lòng lập lại báo giá");
        }
    }

    private static BusinessException inProgress() {
        return new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_IN_PROGRESS",
                "Khoản thanh toán trước đang được ghi nhận vào sổ nợ; vui lòng thử lại sau ít phút");
    }
}
