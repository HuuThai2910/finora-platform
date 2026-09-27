package com.finora.loan.messaging.event;

public record FundingAllocationEventData(
        Long commitmentId,
        String investorId,
        String amount,
        String sharePercent
) {
}
