package com.finora.loan.domain.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DisbursementSagaTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Test
    void completesOnlyAfterPaymentThenCore() {
        DisbursementSaga saga = paidSaga();
        saga.startCoreBooking(NOW.plusSeconds(2), NOW.plusSeconds(2).plus(LEASE));
        assertThat(saga.complete(1, 9001L, NOW.plusSeconds(3), NOW.plusSeconds(3))).isTrue();
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.COMPLETED);
        assertThat(saga.getPaymentReference()).isEqualTo("PAY-1");
        assertThat(saga.getFineractLoanId()).isEqualTo(9001L);
        assertThat(saga.getNextRetryAt()).isNull();
    }

    @Test
    void coreFailureKeepsPaymentReferenceAndMovesToRepair() {
        DisbursementSaga saga = paidSaga();
        saga.startCoreBooking(NOW, NOW.plus(LEASE));
        assertThat(saga.failCore(1, "FINERACT_REJECTED", "invalid", false, null, NOW)).isTrue();
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(saga.getPaymentReference()).isEqualTo("PAY-1");
    }

    @Test
    void expiredCoreBookingLeaseCanBeReclaimedAndStaleResultIsIgnored() {
        DisbursementSaga saga = paidSaga();
        saga.startCoreBooking(NOW, NOW.plus(LEASE));

        assertThat(saga.isCoreBookingLeaseExpired(NOW.plus(LEASE).minusSeconds(1))).isFalse();
        assertThatThrownBy(() -> saga.startCoreBooking(NOW.plusSeconds(10), NOW.plusSeconds(10).plus(LEASE)))
                .isInstanceOf(IllegalStateException.class);

        Instant expired = NOW.plus(LEASE);
        assertThat(saga.isCoreBookingLeaseExpired(expired)).isTrue();
        saga.startCoreBooking(expired, expired.plus(LEASE));
        assertThat(saga.getAttemptCount()).isEqualTo(2);

        // Lần thử 1 trả kết quả muộn sau khi lần thử 2 đã nhận lease: không được ghi đè.
        assertThat(saga.complete(1, 9001L, expired.plusSeconds(1), expired.plusSeconds(1))).isFalse();
        assertThat(saga.failCore(1, "LATE", "late", true, expired, expired)).isFalse();
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.CORE_BOOKING);
        assertThat(saga.complete(2, 9001L, expired.plusSeconds(2), expired.plusSeconds(2))).isTrue();
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.COMPLETED);
    }

    @Test
    void stalledCoreBookingStopsForManualRepair() {
        DisbursementSaga saga = paidSaga();
        saga.startCoreBooking(NOW, NOW.plus(LEASE));
        assertThatThrownBy(() -> saga.markCoreBookingStalled(NOW)).isInstanceOf(IllegalStateException.class);

        saga.markCoreBookingStalled(NOW.plus(LEASE));

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(saga.getLastErrorCode()).isEqualTo("CORE_BOOKING_STALLED");
        assertThat(saga.getNextRetryAt()).isNull();
    }

    @Test
    void paymentFailureOnlyAppliesWhileWaitingForPayment() {
        DisbursementSaga waiting = DisbursementSaga.waitingPayment(
                1L, "LC-1", 2L, "borrower", new BigDecimal("1000000.00"), NOW);
        assertThat(waiting.paymentFailed("MOCK_REJECTED", "rejected", NOW)).isTrue();
        assertThat(waiting.getStatus()).isEqualTo(DisbursementSagaStatus.PAYMENT_FAILED);
        // Event lặp với eventId mới: không đổi gì và không bù trừ lần hai.
        assertThat(waiting.paymentFailed("MOCK_REJECTED", "rejected", NOW)).isFalse();

        DisbursementSaga paid = paidSaga();
        assertThatThrownBy(() -> paid.paymentFailed("MOCK_REJECTED", "late", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paid.getStatus()).isEqualTo(DisbursementSagaStatus.CORE_BOOKING_PENDING);
    }

    private static DisbursementSaga paidSaga() {
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                1L, "LC-1", 2L, "borrower", new BigDecimal("50000000.00"), NOW);
        saga.paymentCompleted("PAY-1", NOW.plusSeconds(1));
        return saga;
    }
}
