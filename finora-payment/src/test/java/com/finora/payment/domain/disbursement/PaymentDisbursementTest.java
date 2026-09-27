package com.finora.payment.domain.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDisbursementTest {
    @Test
    void recordsProviderReferenceBeforeCompletion() {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        PaymentDisbursement value = PaymentDisbursement.requested(
                UUID.randomUUID(), 1L, "LC-1", 2L, "borrower",
                new BigDecimal("2000000.00"), "VND", "MOCK", now);
        value.start(now.plusSeconds(1));
        value.complete("MOCK-PAY-1", now.plusSeconds(2));
        assertThat(value.getStatus()).isEqualTo(PaymentDisbursementStatus.COMPLETED);
        assertThat(value.getProviderReference()).isEqualTo("MOCK-PAY-1");
        assertThat(value.getAttemptCount()).isEqualTo(1);
    }
}
