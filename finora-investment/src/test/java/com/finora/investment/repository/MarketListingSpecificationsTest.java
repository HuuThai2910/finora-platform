package com.finora.investment.repository;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.dto.request.ListingAdvancedFilter;
import com.finora.investment.exception.InvestmentDomainException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Bảng lọc trang Gọi vốn: dựng điều kiện Criteria (kiểm trên mock) và kiểm khoảng của bộ lọc. */
class MarketListingSpecificationsTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @SuppressWarnings("unchecked")
    private final Root<MarketListing> root = mock(Root.class, RETURNS_DEEP_STUBS);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);

    @Test
    @SuppressWarnings("unchecked")
    void advancedFilterBuildsRangesProgressAndVietnamDates() {
        ListingAdvancedFilter filter = new ListingAdvancedFilter(new BigDecimal("10000000"), null,
                new BigDecimal("50"), null, null, new BigDecimal("18"), 6, null, List.of("a", "B"), 60, null,
                "education", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9), null, null);

        MarketListingSpecifications.matching(ListingStatus.OPEN, "A", null, 12, "42", null, null, filter, VN)
                .toPredicate(root, query, cb);

        verify(cb).equal(any(), eq(ListingStatus.OPEN));
        verify(cb).equal(any(), eq("A"));
        verify(cb).lessThanOrEqualTo(any(Expression.class), eq(12));
        verify(cb).notEqual(any(), eq("42"));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(new BigDecimal("10000000")));
        verify(cb).lessThanOrEqualTo(any(Expression.class), eq(new BigDecimal("18")));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(6));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(60));
        // Tiến độ so bằng phép nhân: committed × 100 >= target × 50.
        verify(cb).prod(any(Expression.class), eq(new BigDecimal("50")));
        verify(cb).equal(any(), eq("EDUCATION"));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(Instant.parse("2026-10-31T17:00:00Z")));
        verify(cb).lessThan(any(Expression.class), eq(Instant.parse("2026-11-09T17:00:00Z")));
    }

    @Test
    void filterValidationRejectsReversedAndOutOfScaleValues() {
        assertThatThrownBy(() -> new ListingAdvancedFilter(null, null, null, null, new BigDecimal("20"),
                new BigDecimal("10"), null, null, null, null, null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(InvestmentDomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
        assertThatThrownBy(() -> new ListingAdvancedFilter(null, null, new BigDecimal("120"), null, null, null, null,
                null, null, null, null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(InvestmentDomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
        assertThatThrownBy(() -> new ListingAdvancedFilter(new BigDecimal("-1"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(InvestmentDomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_VALUE_INVALID"));
    }

    @Test
    void emptinessAndNormalisation() {
        assertThat(ListingAdvancedFilter.NONE.isEmpty()).isTrue();
        ListingAdvancedFilter filter = new ListingAdvancedFilter(null, null, null, null, null, null, null, null,
                List.of(" b", "B", ""), null, null, " ", null, null, null, null);
        assertThat(filter.grades()).containsExactly("B");
        assertThat(filter.purposeCode()).isNull();
        assertThat(filter.isEmpty()).isFalse();
    }
}
