package com.finora.loan.integration.fineract.client;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CoreLoanBookingGateway {
    CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand command);

    record CoreLoanBookingCommand(Long loanApplicationId, String contractNumber, String borrowerId,
            Long productId, BigDecimal principal, Integer termMonths, BigDecimal annualRate,
            String coreConfigVersion, LocalDate disbursementDate, String paymentReference) {}
    record CoreLoanBookingResult(Long fineractLoanId) {}
}

