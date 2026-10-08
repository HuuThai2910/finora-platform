package com.finora.loan.domain.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Chuyển từ LoanServicingSyncServiceTest khi công thức nhóm nợ được gom về một chỗ. */
class DebtGroupTest {
    @ParameterizedTest
    @CsvSource({"0,1", "1,1", "9,1", "10,2", "90,2", "91,3", "180,3",
            "181,4", "360,4", "361,5"})
    void classifiesInternalDebtGroupByDpd(int dpd, int expectedGroup) {
        assertThat(DebtGroup.fromDaysPastDue(dpd)).isEqualTo(expectedGroup);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "3,true", "4,true", "5,true"})
    void treatsGroupsThreeToFiveAsNonPerforming(int group, boolean expected) {
        assertThat(DebtGroup.isNonPerforming(group)).isEqualTo(expected);
    }
}
