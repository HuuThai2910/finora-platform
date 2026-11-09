package com.finora.loan.repository.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Dựng điều kiện bảng lọc: khoảng ngày nộp đổi theo giờ Việt Nam (đến hết ngày "đến"), khoảng số dùng >=/<=, điểm/hạng
 * đi qua subquery lần chấm mới nhất. Không có DB: kiểm lời gọi Criteria API trên mock.
 */
class LoanApplicationAdminSpecificationsTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @SuppressWarnings("unchecked")
    private final Root<LoanApplication> root = mock(Root.class, RETURNS_DEEP_STUBS);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);

    @Test
    @SuppressWarnings("unchecked")
    void advancedFilterBuildsRangesInVietnamTimeAndLatestAssessmentSubquery() {
        AdminApplicationFilter filter = new AdminApplicationFilter(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9),
                new BigDecimal("10000000"), new BigDecimal("50000000"), 6, 24,
                new BigDecimal("60"), null, List.of("A"), null, new BigDecimal("18"),
                "VAY_TIEU_DUNG", LoanPurpose.EDUCATION);

        LoanApplicationAdminSpecifications.matching(LoanApplicationStatus.APPROVED, null, null, null, null, filter, VN)
                .toPredicate(root, query, cb);

        verify(cb).equal(any(), eq(LoanApplicationStatus.APPROVED));
        // 01/11 00:00 giờ VN = 31/10 17:00 UTC; "đến 09/11" là trước 10/11 00:00 giờ VN.
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(Instant.parse("2026-10-31T17:00:00Z")));
        verify(cb).lessThan(any(Expression.class), eq(Instant.parse("2026-11-09T17:00:00Z")));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(new BigDecimal("10000000")));
        verify(cb).lessThanOrEqualTo(any(Expression.class), eq(new BigDecimal("50000000")));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(6));
        verify(cb).lessThanOrEqualTo(any(Expression.class), eq(24));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(new BigDecimal("60")));
        verify(cb).lessThanOrEqualTo(any(Expression.class), eq(new BigDecimal("18")));
        verify(cb).equal(any(), eq("VAY_TIEU_DUNG"));
        verify(cb).equal(any(), eq(LoanPurpose.EDUCATION));
        verify(root).get("latestCreditAssessmentId");
        verify(query).subquery(Long.class);
    }

    @Test
    void emptyFilterAddsNoAdvancedCondition() {
        LoanApplicationAdminSpecifications.matching(null, "42", null, null, null, AdminApplicationFilter.NONE, VN)
                .toPredicate(root, query, cb);

        verify(cb).equal(any(), eq("42"));
        verify(root, never()).get("latestCreditAssessmentId");
        verify(root, never()).get("productCodeSnapshot");
        verify(cb, never()).like(any(), anyString(), eq('!'));
    }
}
