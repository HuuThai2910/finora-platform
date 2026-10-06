package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record LoanDisbursedEventData(UUID sagaId, Long loanApplicationId, String applicationNumber,
        String contractNumber, Long listingId, String amount, String currency,
        String paymentReference, Long fineractLoanId, String coreConfigVersion, Instant disbursedAt) {}

