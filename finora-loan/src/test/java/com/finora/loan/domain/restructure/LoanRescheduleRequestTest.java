package com.finora.loan.domain.restructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanRescheduleRequestTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void pendingRequestDoesNotBecomeExecutableUntilAdminApproves() {
        LoanRescheduleRequest request = extension();

        assertThat(request.claim(NOW, Duration.ofMinutes(2))).isNull();
        request.approve("admin", "Đã thẩm định", "decision-1", NOW);

        assertThat(request.claim(NOW, Duration.ofMinutes(2))).isEqualTo(LoanRescheduleStep.CREATE);
        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.CREATING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void staleCreateIsOnlyTakenOverAfterLeaseAndReconcilesBeforeRetry() {
        LoanRescheduleRequest request = extension();
        request.approve("admin", null, "decision-1", NOW);
        request.claim(NOW, Duration.ofMinutes(2));

        assertThat(request.claim(NOW.plusSeconds(119), Duration.ofMinutes(2))).isNull();
        assertThat(request.claim(NOW.plusSeconds(120), Duration.ofMinutes(2)))
                .isEqualTo(LoanRescheduleStep.CREATE);
    }

    @Test
    void retryMovesToManualReviewAtConfiguredLimit() {
        LoanRescheduleRequest request = extension();
        request.approve("admin", null, "decision-1", NOW);
        request.claim(NOW, Duration.ofMinutes(2));

        request.fail("FINERACT_TIMEOUT", true, 1, NOW.plusSeconds(30), NOW);

        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.MANUAL_REVIEW);
        assertThat(request.getNextAttemptAt()).isNull();
    }

    @Test
    void requestShapeSeparatesInstallmentAdjustmentFromExtension() {
        assertThatThrownBy(() -> LoanRescheduleRequest.submit(UUID.randomUUID(), 1L, "LN-1", "b",
                LoanRescheduleType.TERM_EXTENSION, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 10), 2, "Khó khăn", "V1", HASH,
                "idem", HASH, LocalDate.of(2027, 1, 1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LoanRescheduleRequest extension() {
        return LoanRescheduleRequest.submit(UUID.randomUUID(), 1L, "LN-1", "borrower",
                LoanRescheduleType.TERM_EXTENSION, LocalDate.of(2026, 11, 1), null, 2,
                "Thu nhập tạm thời giảm", "V1", HASH, "idem", HASH,
                LocalDate.of(2027, 1, 1), NOW);
    }
}

