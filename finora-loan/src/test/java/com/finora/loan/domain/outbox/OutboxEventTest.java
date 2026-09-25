package com.finora.loan.domain.outbox;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void retriesWithNewAvailabilityThenPublishesWithMatchingClaim() {
        OutboxEvent event = event();
        UUID firstClaim = UUID.randomUUID();

        assertThat(event.claim(NOW, NOW.minusSeconds(60), firstClaim)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.getAttemptCount()).isEqualTo(1);

        Instant retryAt = NOW.plusSeconds(5);
        event.markPublishFailure(
                "BROKER_UNAVAILABLE", "timeout", true, 3, retryAt, firstClaim, NOW.plusSeconds(1));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAvailableAt()).isEqualTo(retryAt);

        UUID secondClaim = UUID.randomUUID();
        assertThat(event.claim(retryAt, retryAt.minusSeconds(60), secondClaim)).isTrue();
        event.markPublished(secondClaim, retryAt.plusSeconds(1));

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getPublishedAt()).isEqualTo(retryAt.plusSeconds(1));
    }

    @Test
    void staleClaimCannotAcknowledgeEventAndPermanentFailureGoesDeadLetter() {
        OutboxEvent event = event();
        UUID firstClaim = UUID.randomUUID();
        event.claim(NOW, NOW.minusSeconds(60), firstClaim);

        UUID takeover = UUID.randomUUID();
        assertThat(event.claim(NOW.plusSeconds(121), NOW.plusSeconds(61), takeover)).isTrue();
        assertThatThrownBy(() -> event.markPublished(firstClaim, NOW.plusSeconds(122)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim");

        event.markPublishFailure(
                "INVALID_EVENT", "schema", false, 10,
                NOW.plusSeconds(130), takeover, NOW.plusSeconds(122));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
    }

    private OutboxEvent event() {
        return OutboxEvent.create(
                UUID.randomUUID(), "LoanContract", "LC-001", "LoanContractCreated", 1,
                "{\"contractNumber\":\"LC-001\"}", "trace-001", NOW);
    }
}
