package com.finora.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Máy trạng thái một lần gửi push: claim token, retry có giới hạn, dead-letter và gửi lại. */
class PushDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    @Test
    void lateResultOfAnExpiredClaimIsIgnoredBecauseTheClaimTokenChanged() {
        PushDelivery delivery = pending();
        delivery.claim("EXPO", NOW.plusSeconds(120), NOW);
        int firstClaim = delivery.getAttemptCount();
        // Worker đầu chết; hết lease, worker khác nhận lại.
        delivery.claim("EXPO", NOW.plusSeconds(400), NOW.plusSeconds(200));

        assertThat(delivery.isClaimedBy(firstClaim)).isFalse();
        assertThat(delivery.isClaimedBy(delivery.getAttemptCount())).isTrue();
    }

    @Test
    void transientFailuresRetryUntilMaxAttemptsThenDeadLetter() {
        PushDelivery delivery = pending();
        for (int attempt = 1; attempt <= 3; attempt++) {
            delivery.claim("EXPO", NOW.plusSeconds(120), NOW);
            delivery.failRetryable("EXPO_HTTP_503", NOW.plusSeconds(60L * attempt), 3, NOW);
        }

        assertThat(delivery.getStatus()).isEqualTo(PushDeliveryStatus.DEAD);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastErrorCode()).isEqualTo("EXPO_HTTP_503");
    }

    @Test
    void requeueStartsAFreshAttemptBudget() {
        PushDelivery delivery = pending();
        delivery.claim("EXPO", NOW.plusSeconds(120), NOW);
        delivery.failRetryable("InvalidCredentials", NOW, 1, NOW);
        assertThat(delivery.isRequeueable()).isTrue();

        delivery.requeue(NOW.plusSeconds(3600));

        assertThat(delivery.getStatus()).isEqualTo(PushDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void acceptedTicketWaitsForReceiptThenConfirms() {
        PushDelivery delivery = pending();
        delivery.claim("LOG_MOCK", NOW.plusSeconds(120), NOW);
        delivery.accepted("mock-1", NOW.plusSeconds(900), NOW);
        assertThat(delivery.getStatus()).isEqualTo(PushDeliveryStatus.SENT);
        assertThat(delivery.getReceiptCheckAfter()).isEqualTo(NOW.plusSeconds(900));

        delivery.confirm(NOW.plusSeconds(901));

        assertThat(delivery.getStatus()).isEqualTo(PushDeliveryStatus.CONFIRMED);
        assertThat(delivery.isRequeueable()).isFalse();
    }

    private static PushDelivery pending() {
        InAppNotification notification = InAppNotification.create(UUID.randomUUID(), "42", "LOAN_DISBURSED",
                NotificationCategory.DISBURSEMENT, 1, "Khoản vay đã được giải ngân", "...", "12", true, NOW, NOW);
        PushDevice device = PushDevice.register("42", "ExponentPushToken[FAKE-test-token-0001]", "a".repeat(64),
                DevicePlatform.ANDROID, "1.0.0", NOW);
        return PushDelivery.pending(notification, device, "Giải ngân", "Khoản vay của bạn đã được giải ngân.", NOW, NOW);
    }
}
