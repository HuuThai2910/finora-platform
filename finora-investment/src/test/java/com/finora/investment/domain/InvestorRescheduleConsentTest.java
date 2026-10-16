package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.investment.domain.restructure.InvestorRescheduleConsent;
import com.finora.investment.domain.restructure.InvestorRescheduleConsentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestorRescheduleConsentTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    @Test
    void investorCanApproveOnlyOnceAndSameKeyIsRecognized() {
        InvestorRescheduleConsent consent = pending();

        consent.approve("approve-key", "Đồng ý", NOW.plusSeconds(60));

        assertThat(consent.getStatus()).isEqualTo(InvestorRescheduleConsentStatus.APPROVED);
        assertThat(consent.sameDecision("approve-key")).isTrue();
        assertThat(consent.getDecidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> consent.reject("other-key", "Đổi ý", NOW.plusSeconds(120)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelOnlyChangesPendingConsent() {
        InvestorRescheduleConsent pending = pending();
        pending.cancel(NOW.plusSeconds(60));
        assertThat(pending.getStatus()).isEqualTo(InvestorRescheduleConsentStatus.CANCELLED);

        InvestorRescheduleConsent approved = pending();
        approved.approve("approve-key", null, NOW.plusSeconds(60));
        approved.cancel(NOW.plusSeconds(120));
        assertThat(approved.getStatus()).isEqualTo(InvestorRescheduleConsentStatus.APPROVED);
    }

    @Test
    void outstandingPrincipalMustBePositive() {
        assertThatThrownBy(() -> InvestorRescheduleConsent.pending(
                UUID.randomUUID(), 10L, "LN-10", "TERM_EXTENSION",
                LocalDate.of(2026, 11, 1), null, 2, LocalDate.of(2027, 8, 1),
                "investor-1", BigDecimal.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dương");
    }

    private static InvestorRescheduleConsent pending() {
        return InvestorRescheduleConsent.pending(
                UUID.randomUUID(), 10L, "LN-10", "TERM_EXTENSION",
                LocalDate.of(2026, 11, 1), null, 2, LocalDate.of(2027, 8, 1),
                "investor-1", new BigDecimal("5000000.00"), NOW);
    }
}
