package com.finora.loan.integration.fineract.client;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CoreLoanBookingGateway {
    CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand command);

    record CoreLoanBookingCommand(Long loanApplicationId, String contractNumber, String borrowerId,
            Long productId, BigDecimal principal, Integer termMonths, BigDecimal annualRate,
            String coreConfigVersion, LocalDate disbursementDate, String paymentReference) {}
    /**
     * @param maturityDate ngày đáo hạn Fineract tính theo ngày giải ngân thực tế ({@code timeline.expectedMaturityDate});
     *                     {@code null} khi core không trả (gateway giả lập)
     */
    record CoreLoanBookingResult(Long fineractLoanId, LocalDate maturityDate) {
        public CoreLoanBookingResult(Long fineractLoanId) {
            this(fineractLoanId, null);
        }
    }
}

