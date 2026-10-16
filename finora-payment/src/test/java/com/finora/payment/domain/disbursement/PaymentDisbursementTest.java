package com.finora.payment.domain.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDisbursementTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Test
    void recordsProviderReferenceBeforeCompletion() {
        PaymentDisbursement value = requested();
        value.start(NOW.plusSeconds(1), NOW.plusSeconds(1).plus(LEASE));
        value.complete("MOCK-PAY-1", UUID.randomUUID(), NOW.plusSeconds(2));
        assertThat(value.getStatus()).isEqualTo(PaymentDisbursementStatus.COMPLETED);
        assertThat(value.getProviderReference()).isEqualTo("MOCK-PAY-1");
        assertThat(value.getAttemptCount()).isEqualTo(1);
        assertThat(value.getNextAttemptAt()).isNull();
        assertThat(value.acceptsProviderResult()).isFalse();
    }

    @Test
    void processingLeaseExpiresOnlyAfterDeadline() {
        PaymentDisbursement value = requested();
        value.start(NOW, NOW.plus(LEASE));

        assertThat(value.acceptsProviderResult()).isTrue();
        assertThat(value.isProcessingLeaseExpired(NOW.plus(LEASE).minusSeconds(1))).isFalse();
        assertThat(value.isProcessingLeaseExpired(NOW.plus(LEASE))).isTrue();
    }

    @Test
    void reconciliationSchedulesLookupsAndCountsChecks() {
        PaymentDisbursement value = requested();
        value.start(NOW, NOW.plus(LEASE));
        value.requireReconciliation("PAYMENT_PROCESSING_INTERRUPTED", "worker died", NOW.plus(LEASE), NOW.plus(LEASE));

        assertThat(value.getStatus()).isEqualTo(PaymentDisbursementStatus.RECONCILIATION_REQUIRED);
        assertThat(value.acceptsProviderResult()).isTrue();
        value.recordReconciliationCheck(NOW.plus(LEASE).plusSeconds(1), NOW.plus(LEASE).plusSeconds(61));
        assertThat(value.getReconciliationAttemptCount()).isEqualTo(1);
        assertThat(value.getNextAttemptAt()).isEqualTo(NOW.plus(LEASE).plusSeconds(61));

        // Hết lượt tra cứu: không hẹn lần sau, chờ vận hành; không có nhánh nào quay lại gọi chuyển tiền.
        value.recordReconciliationCheck(NOW.plus(LEASE).plusSeconds(62), null);
        assertThat(value.getNextAttemptAt()).isNull();
        assertThat(value.getStatus()).isEqualTo(PaymentDisbursementStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void reconciliationCheckRequiresReconciliationState() {
        PaymentDisbursement value = requested();
        assertThatThrownBy(() -> value.recordReconciliationCheck(NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaymentDisbursement requested() {
        return PaymentDisbursement.requested(
                UUID.randomUUID(), 1L, "LC-1", 2L, "borrower",
                new BigDecimal("2000000.00"), "VND", "MOCK", "[]", NOW);
    }
}
