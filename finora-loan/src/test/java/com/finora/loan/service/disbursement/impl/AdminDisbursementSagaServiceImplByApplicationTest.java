package com.finora.loan.service.disbursement.impl;

import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saga giải ngân của một hồ sơ cho trang hồ sơ: chỉ trả khi đúng trạng thái cần xem (mặc định chờ vận hành). */
class AdminDisbursementSagaServiceImplByApplicationTest {

    private final DisbursementSagaRepository sagas = mock(DisbursementSagaRepository.class);
    private final ApplicationNumberLookupService lookup = mock(ApplicationNumberLookupService.class);
    private final LoanContractRepository contracts = mock(LoanContractRepository.class);
    private final AdminDisbursementSagaServiceImpl service = new AdminDisbursementSagaServiceImpl(
            sagas, lookup, contracts, Clock.systemUTC());

    @Test
    void returnsTheSagaWaitingForOperators() {
        DisbursementSaga saga = saga(DisbursementSagaStatus.REPAIR_REQUIRED);
        when(sagas.findByLoanApplicationId(7L)).thenReturn(Optional.of(saga));
        when(lookup.referencesOf(anyList())).thenReturn(List.of());
        when(contracts.findContractCodes(anyList())).thenReturn(List.of());

        var page = service.listByApplication(7L, null);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.data()).singleElement().satisfies(row -> {
            assertThat(row.loanApplicationId()).isEqualTo(7L);
            assertThat(row.status()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        });
        verify(sagas, never()).findByStatusOrderByUpdatedAtAscIdAsc(any(), any());
    }

    @Test
    void completedSagaIsNotAnIncident() {
        DisbursementSaga saga = saga(DisbursementSagaStatus.COMPLETED);
        when(sagas.findByLoanApplicationId(7L)).thenReturn(Optional.of(saga));

        var page = service.listByApplication(7L, null);

        assertThat(page.data()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void applicationWithoutSagaIsEmpty() {
        when(sagas.findByLoanApplicationId(8L)).thenReturn(Optional.empty());

        assertThat(service.listByApplication(8L, DisbursementSagaStatus.REPAIR_REQUIRED).data()).isEmpty();
    }

    private static DisbursementSaga saga(DisbursementSagaStatus status) {
        DisbursementSaga saga = mock(DisbursementSaga.class);
        when(saga.getSagaId()).thenReturn(UUID.fromString("6f1c2c1e-6a7b-4c84-9d0f-2a0e8b7f1a11"));
        when(saga.getLoanApplicationId()).thenReturn(7L);
        when(saga.getContractNumber()).thenReturn("LC-7");
        when(saga.getStatus()).thenReturn(status);
        return saga;
    }
}
