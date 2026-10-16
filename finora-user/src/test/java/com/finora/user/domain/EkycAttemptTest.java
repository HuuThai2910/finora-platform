package com.finora.user.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vòng đời lượt eKYC: chỉ những chuyển trạng thái hợp lệ mới được phép. */
class EkycAttemptTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final UUID REVIEWER = UUID.randomUUID();

    private static EkycAttempt open() {
        return EkycAttempt.start(UUID.randomUUID(), 7L, 5L, "CCCD_CHIP_2021", EkycDocumentDecision.NEEDS_REVIEW,
                "[]", "doc-v", NOW);
    }

    @Test
    void reviewThenApprove() {
        EkycAttempt attempt = open();
        attempt.recordLiveness(EkycLivenessResult.REVIEW, "[\"FACE_MATCH_GRAY\"]", 0.4, "doc-v,live-v", NOW);
        attempt.submitForReview("{}", NOW);
        attempt.approve(REVIEWER, "ok", NOW);

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        assertThat(attempt.getReviewerKeycloakId()).isEqualTo(REVIEWER);
        assertThat(attempt.getDecidedAt()).isEqualTo(NOW);
        assertThat(attempt.getThresholdsVersion()).isEqualTo("doc-v,live-v");
    }

    @Test
    void cannotDecideTwiceOrDecideOpenAttempt() {
        EkycAttempt attempt = open();
        assertThatThrownBy(() -> attempt.approve(REVIEWER, null, NOW)).isInstanceOf(IllegalStateException.class);

        attempt.submitForReview("{}", NOW);
        attempt.reject(REVIEWER, "sai", NOW);
        assertThatThrownBy(() -> attempt.approve(REVIEWER, null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abandonOnlyTouchesOpenAttempts() {
        EkycAttempt attempt = open();
        attempt.markVerified(NOW);
        attempt.abandon(NOW);

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.VERIFIED);
    }

    @Test
    void withdrawalAbandonsPendingReviewAndClearsDraft() {
        EkycAttempt attempt = open();
        attempt.submitForReview("{\"idNumber\":\"x\"}", NOW);

        attempt.withdraw(NOW);

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
        assertThat(attempt.getDraftEncrypted()).isNull();
    }

    @Test
    void longTextsAreTruncatedToColumnSizes() {
        EkycAttempt attempt = EkycAttempt.start(UUID.randomUUID(), 7L, null, null, EkycDocumentDecision.AUTO_OK,
                null, "v".repeat(100), NOW);
        attempt.submitForReview("{}", NOW);
        attempt.reject(REVIEWER, "n".repeat(1200), NOW);

        assertThat(attempt.getThresholdsVersion()).hasSize(80);
        assertThat(attempt.getReviewNote()).hasSize(1000);
    }
}
