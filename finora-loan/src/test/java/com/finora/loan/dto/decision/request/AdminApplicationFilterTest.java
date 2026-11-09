package com.finora.loan.dto.decision.request;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminApplicationFilterTest {

    private static AdminApplicationFilter amount(String min, String max) {
        return new AdminApplicationFilter(null, null, min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), null, null, null, null, null, null, null, null, null);
    }

    @Test
    void emptyFilterIsRecognisedSoTheOldQueriesStay() {
        assertThat(AdminApplicationFilter.NONE.isEmpty()).isTrue();
        assertThat(new AdminApplicationFilter(null, null, null, null, null, null, null, null,
                Arrays.asList(" ", null), null, null, "  ", null).isEmpty()).isTrue();
        assertThat(amount("1000000", null).isEmpty()).isFalse();
    }

    @Test
    void gradesAreTrimmedUppercasedAndDeduplicated() {
        AdminApplicationFilter filter = new AdminApplicationFilter(null, null, null, null, null, null, null, null,
                List.of(" a", "A", "b "), null, null, null, null);
        assertThat(filter.grades()).containsExactly("A", "B");
    }

    @Test
    void reversedRangeIsRejectedWithCode() {
        assertThatThrownBy(() -> amount("50000000", "10000000").validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
        assertThatThrownBy(() -> new AdminApplicationFilter(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 1),
                null, null, null, null, null, null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
    }

    @Test
    void negativeValuesAndScoresOutsideScaleAreRejected() {
        assertThatThrownBy(() -> amount("-1", null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
        assertThatThrownBy(() -> new AdminApplicationFilter(null, null, null, null, -1, null, null, null, null,
                null, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
        assertThatThrownBy(() -> new AdminApplicationFilter(null, null, null, null, null, null, null,
                new BigDecimal("101"), null, null, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
    }

    @Test
    void equalBoundsAreAllowed() {
        assertThat(amount("10000000", "10000000").validate().amountMin()).isEqualByComparingTo("10000000");
    }
}
