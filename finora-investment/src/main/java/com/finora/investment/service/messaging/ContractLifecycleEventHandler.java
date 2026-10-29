package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.investment.messaging.event.LoanContractActivatedEventData;

import java.time.Instant;
import java.util.UUID;

/** Cập nhật projection Contract cho UI Investment; tuyệt đối không biến Investment thành owner Contract. */
public interface ContractLifecycleEventHandler {

    void handleSignatureRequested(
            UUID eventId,
            Instant occurredAt,
            InvestorSignatureRequestedEventData data
    );

    void handleActivated(
            UUID eventId,
            Instant occurredAt,
            LoanContractActivatedEventData data
    );
}
