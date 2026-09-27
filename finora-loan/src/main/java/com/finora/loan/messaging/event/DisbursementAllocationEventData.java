package com.finora.loan.messaging.event;

public record DisbursementAllocationEventData(Long commitmentId, String investorId, String amount) {}

