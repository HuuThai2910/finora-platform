package com.finora.loan.domain.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanServicingProjectionTest {
    @Test
    void appliesFineractRepaymentProjection() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        LoanServicingProjection projection = LoanServicingProjection.fromContractSnapshot(1L,
                new BigDecimal("10000"), new BigDecimal("1200"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("11200"), LocalDate.of(2026, 11, 1), new BigDecimal("1000"),
                LocalDate.of(2027, 10, 1), created);

        Instant paid = Instant.parse("2026-10-03T00:00:00Z");
        projection.applyRepayment(new BigDecimal("800"), new BigDecimal("200"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("9200"), new BigDecimal("1000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("10200"), BigDecimal.ZERO, LocalDate.of(2026, 12, 1),
                new BigDecimal("1000"), paid);

        assertThat(projection.getPrincipalPaid()).isEqualByComparingTo("800.00");
        assertThat(projection.getPrincipalOutstanding()).isEqualByComparingTo("9200.00");
        assertThat(projection.getInterestPaid()).isEqualByComparingTo("200.00");
        assertThat(projection.getTotalOutstanding()).isEqualByComparingTo("10200.00");
        assertThat(projection.isStale()).isFalse();
        assertThat(projection.getSource()).isEqualTo("FINERACT_REPAYMENT_EVENT");
        assertThat(projection.getDaysPastDue()).isZero();
        assertThat(projection.getOverdueSince()).isNull();
    }

    @Test
    void appliesOverdueSnapshotFromFineractWithoutRecalculatingMoney() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        LoanServicingProjection projection = LoanServicingProjection.fromContractSnapshot(1L,
                new BigDecimal("10000"), new BigDecimal("1200"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("11200"), LocalDate.of(2026, 10, 1), new BigDecimal("1000"),
                LocalDate.of(2027, 10, 1), created);

        projection.applyCoreSnapshot(new LoanServicingSnapshot(88L, "LC-001", "loanStatusType.active",
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("10000"),
                new BigDecimal("1200"), BigDecimal.ZERO, new BigDecimal("1200"),
                BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("11210"),
                new BigDecimal("1010"), LocalDate.of(2026, 9, 23), 10,
                LocalDate.of(2026, 9, 23), new BigDecimal("1010"), LocalDate.of(2027, 10, 1)),
                Instant.parse("2026-10-03T00:00:00Z"));

        assertThat(projection.getDaysPastDue()).isEqualTo(10);
        assertThat(projection.getOverdueSince()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(projection.getPenaltyOutstanding()).isEqualByComparingTo("10.00");
        assertThat(projection.getSource()).isEqualTo("FINERACT_SERVICING_SYNC");
    }
}
