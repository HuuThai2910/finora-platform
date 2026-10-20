package com.finora.payment.domain.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.payment.domain.wallet.WalletOwnerType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentWithdrawalTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");

    @Test
    void happyPathMovesForwardOnlyAndCompletionIsIdempotent() {
        PaymentWithdrawal value = requested();

        value.startProcessing(NOW, NOW.plus(Duration.ofMinutes(2)));
        value.providerAccepted("MOCKPAYOUT-1", NOW, NOW.plus(Duration.ofMinutes(2)));

        assertThat(value.complete("MOCKPAYOUT-1", NOW)).isTrue();
        assertThat(value.complete("MOCKPAYOUT-1", NOW)).isFalse();
        assertThat(value.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(value.getNextAttemptAt()).isNull();
        assertThat(value.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void failedWithdrawalNeedsItsRefundAndCannotBeCompletedLater() {
        PaymentWithdrawal value = requested();
        value.startProcessing(NOW, NOW.plusSeconds(120));

        assertThatThrownBy(() -> value.fail("X", "y", null, NOW)).isInstanceOf(NullPointerException.class);
        value.fail("MOCK_ACCOUNT_INVALID", "Tài khoản sai", UUID.randomUUID(), NOW);

        assertThat(value.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThatThrownBy(() -> value.complete("R", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredLeaseIsDetectedOnlyWhileProcessing() {
        PaymentWithdrawal value = requested();
        value.startProcessing(NOW, NOW.plusSeconds(120));

        assertThat(value.isProcessingLeaseExpired(NOW.plusSeconds(119))).isFalse();
        assertThat(value.isProcessingLeaseExpired(NOW.plusSeconds(120))).isTrue();
    }

    @Test
    void resubmitAndManualRecheckOnlyFromReconciliation() {
        PaymentWithdrawal value = requested();
        assertThatThrownBy(() -> value.requestManualRecheck("admin", NOW)).isInstanceOf(IllegalStateException.class);

        value.startProcessing(NOW, NOW.plusSeconds(120));
        value.requireReconciliation("WITHDRAWAL_PROCESSING_INTERRUPTED", "lease", NOW, null);
        value.requestManualRecheck("admin-1", NOW);

        assertThat(value.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(value.getManualRecheckCount()).isEqualTo(1);
        value.resubmit(NOW);
        assertThat(value.getStatus()).isEqualTo(WithdrawalStatus.REQUESTED);
        assertThat(value.getProviderReference()).isNull();
    }

    private static PaymentWithdrawal requested() {
        return PaymentWithdrawal.request(UUID.randomUUID(), UUID.randomUUID(), WalletOwnerType.INVESTOR, "INV-1",
                new BigDecimal("500000"), BigDecimal.ZERO, "VND", LocalDate.of(2026, 10, 17),
                new PaymentWithdrawal.Destination("VCB", "v1:x", "******6789", "NGUYEN VAN A"),
                "idem-1", "a".repeat(64), "MOCK", UUID.randomUUID(), NOW);
    }
}
