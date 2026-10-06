package com.finora.loan.domain.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FinoraLoanTest {
    @Test
    void settlesAnActiveLoan() {
        Instant disbursed = Instant.parse("2026-10-01T00:00:00Z");
        FinoraLoan loan = FinoraLoan.activate("LN-1", 1L, "LA-1", "LC-1", "borrower", 9L,
                new BigDecimal("10000000"), "VND", disbursed);
        Instant settled = Instant.parse("2026-10-03T00:00:00Z");

        loan.settle(settled);

        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.SETTLED);
        assertThat(loan.getClosedAt()).isEqualTo(settled);
    }
}
