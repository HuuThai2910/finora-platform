package com.finora.payment.integration.fineract;

import com.finora.payment.domain.repayment.PaymentRepayment;
import java.util.Optional;

public interface RepaymentCoreGateway {
    ScheduledRepaymentCoreQuote scheduledRepaymentQuote(Long fineractLoanId);

    EarlySettlementCoreQuote earlySettlementQuote(Long fineractLoanId, java.time.LocalDate transactionDate);

    PartialPrepaymentCoreSnapshot partialPrepaymentSnapshot(Long fineractLoanId,
            java.time.LocalDate transactionDate);

    PaymentRepayment.CoreBreakdown postRepayment(RepaymentCoreCommand command);

    /** Chỉ đọc để xác định lệnh trước đã được core ghi hay chưa; tuyệt đối không tạo transaction mới. */
    Optional<PaymentRepayment.CoreBreakdown> findPostedRepayment(Long fineractLoanId, String repaymentReference);
}
