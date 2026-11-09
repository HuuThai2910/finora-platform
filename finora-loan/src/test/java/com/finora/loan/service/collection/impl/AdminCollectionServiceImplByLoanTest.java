package com.finora.loan.service.collection.impl;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
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

/** Phần "Sau giải ngân" của trang hồ sơ chỉ đọc hồ sơ thu hồi của đúng khoản vay, không qua hàng đợi chung. */
class AdminCollectionServiceImplByLoanTest {

    private final LoanCollectionCaseRepository cases = mock(LoanCollectionCaseRepository.class);
    private final AdminCollectionServiceImpl service = new AdminCollectionServiceImpl(
            cases, mock(LoanCollectionActionRepository.class), mock(HashingService.class), Clock.systemUTC());

    @Test
    void listsEveryCaseOfTheLoanWhenStatusIsMissing() {
        when(cases.findByLoanNumberOrderByOpenedAtDesc(eq("LN-7"), any(Pageable.class))).thenReturn(Page.empty());

        service.listByLoan(" LN-7 ", null, 0, 20);

        verify(cases).findByLoanNumberOrderByOpenedAtDesc(eq("LN-7"), any(Pageable.class));
        verify(cases, never()).findAllByOrderByDaysPastDueDescUpdatedAtAsc(any());
    }

    @Test
    void filtersCasesOfTheLoanByStatus() {
        when(cases.findByLoanNumberAndStatusOrderByOpenedAtDesc(eq("LN-7"), eq(CollectionCaseStatus.OPEN),
                any(Pageable.class))).thenReturn(Page.empty());

        service.listByLoan("LN-7", CollectionCaseStatus.OPEN, 0, 20);

        verify(cases).findByLoanNumberAndStatusOrderByOpenedAtDesc(eq("LN-7"), eq(CollectionCaseStatus.OPEN),
                any(Pageable.class));
    }
}
