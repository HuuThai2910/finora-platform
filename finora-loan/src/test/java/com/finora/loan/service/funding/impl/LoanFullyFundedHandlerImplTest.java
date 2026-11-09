package com.finora.loan.service.funding.impl;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.integration.profile.BorrowerIdentityUnavailableException;
import com.finora.loan.integration.profile.client.BorrowerIdentityClient;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.util.HashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Danh tính bên vay được đọc từ finora-user trước transaction lập hợp đồng: chỉ đọc khi event có thể lập (hoặc phát hành
 * lại) hợp đồng, và lỗi đọc thì không mở transaction nào để event được thử lại nguyên vẹn.
 */
class LoanFullyFundedHandlerImplTest {

    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final LoanApplicationRepository loanApplicationRepository = mock(LoanApplicationRepository.class);
    private final LoanContractRepository loanContractRepository = mock(LoanContractRepository.class);
    private final BorrowerIdentityClient borrowerIdentityClient = mock(BorrowerIdentityClient.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final LoanApplication application = mock(LoanApplication.class);
    private LoanFullyFundedHandlerImpl handler;

    @BeforeEach
    void setUp() {
        handler = new LoanFullyFundedHandlerImpl(processedEventRepository, loanApplicationRepository,
                mock(ScheduleCalculationSnapshotRepository.class), mock(LoanContractCreationService.class),
                loanContractRepository, borrowerIdentityClient, mock(LoanContractProperties.class),
                mock(HashingService.class), transactionTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
        // Không chạy phần ghi: test này chỉ kiểm bước đọc danh tính trước transaction.
        doNothing().when(transactionTemplate).executeWithoutResult(any());
        when(application.getId()).thenReturn(10L);
        when(application.getBorrowerId()).thenReturn("42");
        when(loanApplicationRepository.findById(10L)).thenReturn(Optional.of(application));
    }

    @Test
    void readsBorrowerIdentityBeforeOpeningTheTransactionWhenNoContractExists() {
        when(loanContractRepository.findByApplicationId(10L)).thenReturn(Optional.empty());
        when(borrowerIdentityClient.identity("42"))
                .thenReturn(new BorrowerIdentity("Nguyễn Văn An", "********1234", NOW));

        handler.handle(EVENT_ID, NOW, event());

        verify(borrowerIdentityClient).identity("42");
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void identityFailureLeavesNothingWrittenSoTheEventIsRetried() {
        when(loanContractRepository.findByApplicationId(10L)).thenReturn(Optional.empty());
        when(borrowerIdentityClient.identity("42")).thenThrow(new BorrowerIdentityUnavailableException(
                "USER_IDENTITY_UNREACHABLE", "Không kết nối được finora-user", null));

        assertThatThrownBy(() -> handler.handle(EVENT_ID, NOW, event()))
                .isInstanceOf(BorrowerIdentityUnavailableException.class);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void processedEventDoesNotCallUser() {
        when(processedEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        handler.handle(EVENT_ID, NOW, event());

        verify(borrowerIdentityClient, never()).identity(anyString());
    }

    @Test
    void contractThatAlreadyHasAnIdentityDoesNotCallUser() {
        LoanContract contract = mock(LoanContract.class);
        when(contract.isPendingRefunding()).thenReturn(true);
        when(contract.getBorrowerIdentity()).thenReturn(new BorrowerIdentity("Nguyễn Văn An", "********1234", NOW));
        when(loanContractRepository.findByApplicationId(10L)).thenReturn(Optional.of(contract));

        handler.handle(EVENT_ID, NOW, event());

        verify(borrowerIdentityClient, never()).identity(anyString());
    }

    @Test
    void legacyContractReissuedAfterRefundingReadsTheIdentity() {
        LoanContract contract = mock(LoanContract.class);
        when(contract.isPendingRefunding()).thenReturn(true);
        when(contract.getBorrowerIdentity()).thenReturn(null);
        when(loanContractRepository.findByApplicationId(10L)).thenReturn(Optional.of(contract));
        when(borrowerIdentityClient.identity("42")).thenReturn(new BorrowerIdentity(null, null, NOW));

        handler.handle(EVENT_ID, NOW, event());

        verify(borrowerIdentityClient).identity("42");
    }

    private static LoanFullyFundedEventData event() {
        return new LoanFullyFundedEventData(10L, "LA-10", 20L, 1, "10000000.00", "VND", 1L, "a".repeat(64),
                NOW, List.of());
    }
}
