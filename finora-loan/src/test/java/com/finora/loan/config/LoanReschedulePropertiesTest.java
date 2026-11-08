package com.finora.loan.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanReschedulePropertiesTest {

    @Test
    void cannotConfigureTotalContractTermAboveLegalCap() {
        assertThatThrownBy(() -> new LoanRescheduleProperties(
                false, 5_000, 20, 10, null, null, null, null,
                24, 24, 25, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vượt quá 24 tháng");
    }
}
