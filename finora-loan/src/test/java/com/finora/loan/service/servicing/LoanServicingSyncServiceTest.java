package com.finora.loan.service.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoanServicingSyncServiceTest {
    @ParameterizedTest
    @CsvSource({"0,1", "1,1", "9,1", "10,2", "90,2", "91,3", "180,3",
            "181,4", "360,4", "361,5"})
    void classifiesInternalDebtGroupByDpd(int dpd, int expectedGroup) {
        assertThat(LoanServicingSyncService.debtGroup(dpd)).isEqualTo(expectedGroup);
    }
}
