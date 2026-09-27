package com.finora.loan.messaging.event;

import java.util.List;
import java.util.UUID;

public record DisbursementRequestedEventData(
        UUID sagaId, Long loanApplicationId, String applicationNumber, String contractNumber,
        Long listingId, String borrowerId, String amount, String currency,
        List<DisbursementAllocationEventData> allocations
) {}

