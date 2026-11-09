package com.finora.loan.service.restructure.impl;

import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.LoanServicingScheduleVersionRepository;
import com.finora.loan.repository.servicing.PendingScheduledRepaymentRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.restructure.LoanRescheduleService;
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

/** Yêu cầu cơ cấu theo khoản vay cho trang hồ sơ: đọc đúng mã khoản vay, lọc trạng thái tùy chọn. */
class LoanRescheduleAdminByLoanTest {

    private final LoanRescheduleRequestRepository requests = mock(LoanRescheduleRequestRepository.class);
    private final LoanRescheduleService service = new LoanRescheduleServiceImpl(requests,
            mock(FinoraLoanRepository.class), mock(LoanServicingProjectionRepository.class),
            mock(LoanContractRepository.class), mock(ScheduleCalculationSnapshotRepository.class),
            mock(ScheduleCalculationSnapshotMapper.class), mock(LoanServicingScheduleVersionRepository.class),
            mock(PendingScheduledRepaymentRepository.class),
            new LoanRescheduleProperties(true, 5_000, 20, 10, null, null, null, 7L, 6, 6, 24, null, null),
            mock(HashingService.class), mock(OutboxService.class), Clock.systemUTC());

    @Test
    void listsEveryRequestOfTheLoan() {
        when(requests.findByLoanNumberOrderByCreatedAtDesc(eq("LN-7"), any(Pageable.class))).thenReturn(Page.empty());

        service.listAdminByLoan(" LN-7 ", null, 0, 20);

        verify(requests).findByLoanNumberOrderByCreatedAtDesc(eq("LN-7"), any(Pageable.class));
        verify(requests, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void filtersRequestsOfTheLoanByStatus() {
        when(requests.findByLoanNumberAndStatusOrderByCreatedAtDesc(eq("LN-7"),
                eq(LoanRescheduleStatus.PENDING_REVIEW), any(Pageable.class))).thenReturn(Page.empty());

        service.listAdminByLoan("LN-7", LoanRescheduleStatus.PENDING_REVIEW, 0, 20);

        verify(requests).findByLoanNumberAndStatusOrderByCreatedAtDesc(eq("LN-7"),
                eq(LoanRescheduleStatus.PENDING_REVIEW), any(Pageable.class));
    }
}
