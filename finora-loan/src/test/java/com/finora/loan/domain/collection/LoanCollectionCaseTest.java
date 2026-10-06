package com.finora.loan.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanCollectionCaseTest {

    @Test
    void followsOneDelinquencyEpisodeUntilCured() {
        Instant openedAt = Instant.parse("2026-10-01T00:00:00Z");
        LoanCollectionCase collectionCase = LoanCollectionCase.open(
                7L, "LN-007", "borrower-7", 5, money("100000"), money("9000000"),
                LocalDate.of(2026, 9, 26), openedAt);

        collectionCase.observe(91, money("1200000"), money("8500000"),
                LocalDate.of(2026, 7, 2), openedAt.plusSeconds(3600));

        assertThat(collectionCase.getStatus()).isEqualTo(CollectionCaseStatus.OPEN);
        assertThat(collectionCase.getStage()).isEqualTo(CollectionStage.NPL);
        assertThat(collectionCase.getDebtGroup()).isEqualTo(3);

        collectionCase.close(CollectionCaseStatus.CURED, openedAt.plusSeconds(7200));

        assertThat(collectionCase.getStatus()).isEqualTo(CollectionCaseStatus.CURED);
        assertThat(collectionCase.getDaysPastDue()).isZero();
        assertThat(collectionCase.getOverdueAmount()).isEqualByComparingTo("0.00");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
