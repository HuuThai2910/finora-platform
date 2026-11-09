package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.domain.proof.enums.ProofSubmissionStatus;
import com.finora.blockchain.domain.proof.enums.ProofType;
import com.finora.blockchain.dto.request.ProofSearchFilter;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.repository.proof.ProofSubmissionSpecifications;
import com.finora.common.exception.BusinessException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bảng lọc trang Kiểm toán: có bộ lọc thì một truy vấn Specification (trạng thái dùng được cùng tham chiếu), không có
 * thì giữ đường tra cứu cũ; khoảng ngày theo giờ Việt Nam.
 */
class ProofQueryServiceImplFilterTest {

    private final ProofSubmissionRepository repository = mock(ProofSubmissionRepository.class);
    private final ProofQueryServiceImpl service = new ProofQueryServiceImpl(repository);

    @Test
    @SuppressWarnings("unchecked")
    void filterUsesOneSpecificationQueryEvenWithReferenceAndStatus() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search("LOAN_APPLICATION", "123", "CONFIRMED",
                new ProofSearchFilter(ProofType.REPAYMENT, null, null, null), 0, 20);

        verify(repository).findAll(any(Specification.class), any(Pageable.class));
        verify(repository, never()).findByLoanApplicationId(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFilterKeepsTheOldLookupButStatusWithReferenceIsCombined() {
        when(repository.findByLoanApplicationId(eq(123L), any(Pageable.class))).thenReturn(Page.empty());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search("LOAN_APPLICATION", "123", null, ProofSearchFilter.NONE, 0, 20);
        verify(repository).findByLoanApplicationId(eq(123L), any(Pageable.class));

        service.search("LOAN_APPLICATION", "123", "CONFIRMED", ProofSearchFilter.NONE, 0, 20);
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void reversedDatesAreRejected() {
        assertThatThrownBy(() -> service.search(null, null, null, new ProofSearchFilter(null, null,
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 1)), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specificationFiltersTypeProviderAndVietnamDays() {
        Root<ProofSubmission> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);

        ProofSubmissionSpecifications.matching(ProofSubmissionStatus.DEAD_LETTER, null, "Repayment", "R-1",
                        new ProofSearchFilter(ProofType.REPAYMENT, ProofProviderType.HYPERLEDGER_FABRIC,
                                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 9)),
                        ZoneId.of("Asia/Ho_Chi_Minh"))
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).equal(any(), eq(ProofSubmissionStatus.DEAD_LETTER));
        verify(cb).equal(any(), eq("Repayment"));
        verify(cb).equal(any(), eq("R-1"));
        verify(cb).equal(any(), eq(ProofType.REPAYMENT));
        verify(cb).equal(any(), eq(ProofProviderType.HYPERLEDGER_FABRIC));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(Instant.parse("2026-10-31T17:00:00Z")));
        verify(cb).lessThan(any(Expression.class), eq(Instant.parse("2026-11-09T17:00:00Z")));
    }
}
