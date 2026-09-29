package com.finora.investment.messaging.event;

public record FundingAllocationEventData(
        Long commitmentId,
        String investorId,
        String amount,
        String sharePercent,
        String paymentHoldReference
) {
}
