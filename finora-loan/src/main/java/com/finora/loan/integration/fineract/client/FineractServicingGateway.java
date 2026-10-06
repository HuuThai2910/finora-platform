package com.finora.loan.integration.fineract.client;

import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import java.time.LocalDate;

public interface FineractServicingGateway {
    LoanServicingSnapshot read(Long fineractLoanId, LocalDate businessDate);
}
