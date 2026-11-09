package com.finora.user.repository;

import com.finora.common.enums.user.UserRole;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.AccountScope;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileSpecificationsTest {

    @Test
    void plainAlphabetLinesUpWithAccentedOne() {
        assertThat(UserProfileSpecifications.VIETNAMESE_PLAIN)
                .hasSameSizeAs(UserProfileSpecifications.VIETNAMESE_ACCENTED)
                .matches("[aeiouyd]+");
    }

    @Test
    void searchTextIsLowercasedWithoutAccents() {
        assertThat(UserProfileSpecifications.normalizeSearchText("  Trương   Thị HUỆ "))
                .isEqualTo("truong thi hue");
        assertThat(UserProfileSpecifications.normalizeSearchText("Đặng Văn Đức")).isEqualTo("dang van duc");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void roleScopeBecomesInPredicateOnRole() {
        Root<UserProfile> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path rolePath = mock(Path.class);
        Predicate inScope = mock(Predicate.class);
        when(root.get("role")).thenReturn(rolePath);
        when(rolePath.in(any(Collection.class))).thenReturn(inScope);

        UserProfileSpecifications.matching(null, AccountScope.STAFF.roles(), null, null, null, List.of())
                .toPredicate(root, query, cb);

        ArgumentCaptor<Collection> roles = ArgumentCaptor.forClass(Collection.class);
        verify(rolePath).in(roles.capture());
        assertThat((Collection<UserRole>) roles.getValue()).containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.STAFF);
        verify(cb).and(new Predicate[]{inScope});
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void noScopeAddsNoRolePredicate() {
        Root<UserProfile> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path rolePath = mock(Path.class);

        UserProfileSpecifications.matching(null, null, null, null, null, List.of())
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(rolePath, never()).in(any(Collection.class));
        verify(root, never()).get("role");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void advancedFilterUsesVietnamDaysAgeBoundsLockAndLastLoginSubquery() {
        Root<UserProfile> root = mock(Root.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 9);
        var filter = new com.finora.user.dto.request.UserListFilter(java.time.LocalDate.of(2026, 11, 1),
                java.time.LocalDate.of(2026, 11, 9), true, 18, 30, com.finora.common.enums.user.Gender.FEMALE,
                java.time.LocalDate.of(2026, 11, 8), null);

        UserProfileSpecifications.withFilter(filter, today, java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(any(jakarta.persistence.criteria.Expression.class),
                org.mockito.ArgumentMatchers.eq(java.time.Instant.parse("2026-10-31T17:00:00Z")));
        verify(cb).lessThan(any(jakarta.persistence.criteria.Expression.class),
                org.mockito.ArgumentMatchers.eq(java.time.Instant.parse("2026-11-09T17:00:00Z")));
        verify(cb).isNotNull(any());
        // 18 tuổi: sinh trước hoặc đúng 09/11/2008; tối đa 30 tuổi: sinh sau 09/11/1995.
        verify(cb).lessThanOrEqualTo(any(jakarta.persistence.criteria.Expression.class),
                org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2008, 11, 9)));
        verify(cb).greaterThan(any(jakarta.persistence.criteria.Expression.class),
                org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(1995, 11, 9)));
        verify(cb).equal(any(), org.mockito.ArgumentMatchers.eq(com.finora.common.enums.user.Gender.FEMALE));
        verify(query).subquery(java.time.Instant.class);
        verify(cb).greaterThanOrEqualTo(any(jakarta.persistence.criteria.Expression.class),
                org.mockito.ArgumentMatchers.eq(java.time.Instant.parse("2026-11-07T17:00:00Z")));
    }

    @Test
    void emptyAdvancedFilterAddsNothing() {
        @SuppressWarnings("unchecked") Root<UserProfile> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        UserProfileSpecifications.withFilter(com.finora.user.dto.request.UserListFilter.NONE,
                java.time.LocalDate.of(2026, 11, 9), java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).and();
        org.mockito.Mockito.verifyNoInteractions(root);
    }
}
