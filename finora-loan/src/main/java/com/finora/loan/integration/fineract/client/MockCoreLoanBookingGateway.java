package com.finora.loan.integration.fineract.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic demo adapter; cùng contract với adapter Fineract thật và không tạo side effect mạng. */
@Component
@ConditionalOnProperty(name = "finora.fineract.booking-provider", havingValue = "mock", matchIfMissing = true)
public class MockCoreLoanBookingGateway implements CoreLoanBookingGateway {
    @Override
    public CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand command) {
        long id = 1_000_000L + Math.abs(command.contractNumber().hashCode());
        return new CoreLoanBookingResult(id);
    }
}

