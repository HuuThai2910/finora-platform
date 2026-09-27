package com.finora.loan.domain.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DisbursementSagaTest {
    @Test
    void completesOnlyAfterPaymentThenCore() {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                1L, "LC-1", 2L, "borrower", new BigDecimal("50000000.00"), now);
        saga.paymentCompleted("PAY-1", now.plusSeconds(1));
        saga.startCoreBooking(now.plusSeconds(2));
        saga.complete(9001L, now.plusSeconds(3), now.plusSeconds(3));
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.COMPLETED);
        assertThat(saga.getPaymentReference()).isEqualTo("PAY-1");
        assertThat(saga.getFineractLoanId()).isEqualTo(9001L);
    }

    @Test
    void coreFailureKeepsPaymentReferenceAndMovesToRepair() {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                1L, "LC-1", 2L, "borrower", new BigDecimal("1000000.00"), now);
        saga.paymentCompleted("PAY-1", now);
        saga.startCoreBooking(now);
        saga.failCore("FINERACT_REJECTED", "invalid", false, null, now);
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(saga.getPaymentReference()).isEqualTo("PAY-1");
    }
}

