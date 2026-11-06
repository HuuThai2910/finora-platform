package com.finora.loan.service.outbox.impl;

import com.finora.loan.service.outbox.ClaimedOutboxMessage;
import com.finora.loan.service.outbox.OutboxMessage;
import com.finora.loan.service.outbox.OutboxPublishException;
import com.finora.loan.service.outbox.OutboxPublisher;
import com.finora.loan.service.outbox.OutboxRelayStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayServiceImplTest {

    @Test
    void publishesOutsideStateTransactionThenAcknowledgesMatchingClaim() {
        OutboxRelayStateService state = mock(OutboxRelayStateService.class);
        OutboxPublisher publisher = mock(OutboxPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
        ClaimedOutboxMessage claimed = claimed();
        when(state.claim(1L)).thenReturn(claimed);
        when(provider.getIfAvailable()).thenReturn(publisher);

        new OutboxRelayServiceImpl(state, provider).publish(1L);

        verify(publisher).publish(claimed.message());
        verify(state).markPublished(1L, claimed.claimToken());
        verify(state, never()).markFailed(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void classifiesPublisherFailureWithoutLosingClaimedEvent() {
        OutboxRelayStateService state = mock(OutboxRelayStateService.class);
        OutboxPublisher publisher = message -> {
            throw new OutboxPublishException("KAFKA_UNAVAILABLE", "broker down", true, null);
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
        ClaimedOutboxMessage claimed = claimed();
        when(state.claim(1L)).thenReturn(claimed);
        when(provider.getIfAvailable()).thenReturn(publisher);

        new OutboxRelayServiceImpl(state, provider).publish(1L);

        verify(state).markFailed(1L, claimed.claimToken(), "KAFKA_UNAVAILABLE", "broker down", true);
        verify(state, never()).markPublished(1L, claimed.claimToken());
    }

    private ClaimedOutboxMessage claimed() {
        UUID claimToken = UUID.randomUUID();
        return new ClaimedOutboxMessage(
                1L,
                claimToken,
                new OutboxMessage(
                        UUID.randomUUID(), "LoanContract", "LC-001", "LoanContractSigned", 1,
                        "{\"contractNumber\":\"LC-001\"}", "trace-001",
                        Instant.parse("2026-09-17T00:00:00Z")
                )
        );
    }
}
