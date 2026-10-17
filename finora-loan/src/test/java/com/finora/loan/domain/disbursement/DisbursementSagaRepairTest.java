package com.finora.loan.domain.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DisbursementSagaRepairTest {
    private static final Instant NOW = Instant.parse("2026-10-17T05:00:00Z");

    @Test
    void stalledSagaIsRequeuedWithAFreshAttemptBudgetAndAudit() {
        DisbursementSaga saga = stalledSaga();
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(saga.attemptsSinceRepair()).isEqualTo(3);

        saga.requeueAfterRepair("ADMIN-1", "Đã đối chiếu Fineract theo số hợp đồng", NOW.plusSeconds(600));

        // Claim token giữ nguyên (không đặt lại attempt_count) nhưng giới hạn tự động tính lại từ lần sửa.
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.RETRY_PENDING);
        assertThat(saga.getAttemptCount()).isEqualTo(3);
        assertThat(saga.attemptsSinceRepair()).isZero();
        assertThat(saga.getNextRetryAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(saga.getRepairCount()).isEqualTo(1);
        assertThat(saga.getLastRepairBy()).isEqualTo("ADMIN-1");
        assertThat(saga.getLastRepairNote()).contains("Fineract");
        assertThatThrownBy(() -> saga.requeueAfterRepair("ADMIN-1", "lặp lại", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static DisbursementSaga stalledSaga() {
        DisbursementSaga saga = DisbursementSaga.waitingPayment(1L, "LC-1", 2L, "BORROWER-1",
                new BigDecimal("10000000.00"), NOW);
        saga.paymentCompleted("PAY-1", NOW);
        for (int attempt = 1; attempt <= 3; attempt++) {
            // Mỗi lần nhận lại sau khi lease lần trước đã hết hạn (worker chết giữa chừng).
            Instant claimedAt = NOW.plusSeconds(100L * attempt);
            saga.startCoreBooking(claimedAt, claimedAt.plusSeconds(60));
        }
        saga.markCoreBookingStalled(NOW.plusSeconds(3600));
        return saga;
    }
}
