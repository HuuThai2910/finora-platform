package com.finora.user.dto.request;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserListFilterTest {

    @Test
    void reversedRangesAreRejected() {
        assertThatThrownBy(() -> new UserListFilter(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 1),
                null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
        assertThatThrownBy(() -> new UserListFilter(null, null, null, 40, 20, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
    }

    @Test
    void impossibleAgeIsRejected() {
        assertThatThrownBy(() -> new UserListFilter(null, null, null, -1, null, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
        assertThatThrownBy(() -> new UserListFilter(null, null, null, null, 200, null, null, null).validate())
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
    }

    @Test
    void emptinessAndCriteriaDefault() {
        assertThat(UserListFilter.NONE.isEmpty()).isTrue();
        assertThat(new UserListFilter(null, null, false, null, null, null, null, null).isEmpty()).isFalse();
        assertThat(new UserSearchCriteria(null, null, null, null).filter()).isSameAs(UserListFilter.NONE);
        assertThat(new UserSearchCriteria(null, null, null, null, null, null).filter()).isSameAs(UserListFilter.NONE);
    }
}
