package com.finora.loan.service.collection.impl;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.collection.enums.CollectionStage;
import com.finora.loan.repository.collection.LoanCollectionActionRepository;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.util.HashingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ô tìm của hàng đợi thu hồi: có từ khóa thì dùng câu tìm theo mã, không thì giữ các truy vấn lọc cũ. */
class AdminCollectionServiceImplSearchTest {

    private final LoanCollectionCaseRepository cases = mock(LoanCollectionCaseRepository.class);
    private final AdminCollectionServiceImpl service = new AdminCollectionServiceImpl(
            cases, mock(LoanCollectionActionRepository.class), mock(HashingService.class), Clock.systemUTC());

    @Test
    void searchByLoanApplicationOrContractCode() {
        when(cases.search(eq(CollectionCaseStatus.OPEN), eq(null), eq("%HS-20261109-3RXA%"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.list(CollectionCaseStatus.OPEN, null, "hs-20261109-3rxa", 0, 20);

        verify(cases).search(eq(CollectionCaseStatus.OPEN), eq(null), eq("%HS-20261109-3RXA%"), any(Pageable.class));
    }

    @Test
    void withoutSearchUsesTheExistingFilters() {
        when(cases.findByStatusAndStageOrderByDaysPastDueDescUpdatedAtAsc(
                eq(CollectionCaseStatus.OPEN), eq(CollectionStage.values()[0]), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.list(CollectionCaseStatus.OPEN, CollectionStage.values()[0], null, 0, 20);

        verify(cases, never()).search(any(), any(), any(), any());
    }
}
