package com.finora.loan.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectionStageTest {

    @ParameterizedTest
    @CsvSource({
            "1,EARLY_REMINDER", "9,EARLY_REMINDER",
            "10,ATTENTION", "90,ATTENTION",
            "91,NPL", "180,NPL",
            "181,INTENSIVE", "360,INTENSIVE",
            "361,LOSS"
    })
    void mapsDpdBoundaryToCollectionStage(int daysPastDue, CollectionStage expected) {
        assertThat(CollectionStage.fromDaysPastDue(daysPastDue)).isEqualTo(expected);
    }

    @Test
    void rejectsCurrentLoanBecauseItDoesNotNeedCollectionCase() {
        assertThatThrownBy(() -> CollectionStage.fromDaysPastDue(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
