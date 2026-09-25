package com.finora.loan.service.pricing;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.TermsConfirmationStatus;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.application.impl.LoanTermsConfirmationServiceImpl;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanTermsConfirmationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(604800);

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private ScheduleCalculationSnapshotRepository scheduleRepository;
    @Mock private LoanContractRepository contractRepository;
    @Mock private LoanContractCreationService contractCreationService;
    @Mock private LoanApplicationMapper mapper;
    @Mock private LoanContractProperties contractProperties;
    @Mock private LoanPricingDisclosureProperties disclosureProperties;
    @Mock private HashingService hashingService;
    @Mock private Clock clock;
    @Mock private LoanApplication application;
    @Mock private ScheduleCalculationSnapshot initialSchedule;
    @Mock private ScheduleCalculationSnapshot finalSchedule;
    @Mock private LoanContract contract;

    private LoanTermsConfirmationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LoanTermsConfirmationServiceImpl(
                applicationRepository, scheduleRepository, contractRepository,
                contractCreationService, mapper, contractProperties,
                disclosureProperties, hashingService, clock);
        when(application.getId()).thenReturn(10L);
        when(application.getApplicationNumber()).thenReturn("LA-001");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(6);
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal("12.5000"));
        when(application.getFinalAnnualInterestRate()).thenReturn(new BigDecimal("12.0000"));
        when(application.getPricingDisclosureVersionSnapshot()).thenReturn("RATE_DISCLOSURE_V2");
        when(application.getFinalCalculationSnapshotId()).thenReturn(2L);
        when(scheduleRepository.findByApplicationIdAndPurpose(10L, ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .thenReturn(Optional.of(initialSchedule));
        when(initialSchedule.getResponseHash()).thenReturn("a".repeat(64));
        when(initialSchedule.getTotalPrincipal()).thenReturn(new BigDecimal("10000000.00"));
        when(initialSchedule.getTotalFees()).thenReturn(BigDecimal.ZERO);
        when(initialSchedule.getTotalPenalties()).thenReturn(BigDecimal.ZERO);
        when(initialSchedule.getTotalInterest()).thenReturn(new BigDecimal("367000.00"));
        when(initialSchedule.getTotalRepayment()).thenReturn(new BigDecimal("10367000.00"));
        when(initialSchedule.getFirstInstallment()).thenReturn(new BigDecimal("1728000.00"));
        when(initialSchedule.getMaximumInstallment()).thenReturn(new BigDecimal("1729000.00"));
        when(finalSchedule.getId()).thenReturn(2L);
        when(finalSchedule.getResponseHash()).thenReturn("b".repeat(64));
        when(finalSchedule.getTotalPrincipal()).thenReturn(new BigDecimal("10000000.00"));
        when(finalSchedule.getTotalFees()).thenReturn(BigDecimal.ZERO);
        when(finalSchedule.getTotalPenalties()).thenReturn(BigDecimal.ZERO);
        when(contractProperties.termsVersion()).thenReturn("LOAN_TERMS_V1");
        when(disclosureProperties.pricingDisclosureVersion()).thenReturn("RATE_DISCLOSURE_V2");
        when(hashingService.sha256(any())).thenReturn("c".repeat(64));
    }

    @Test
    void createsDemoContractImmediatelyOnlyWhenEveryComparedTermIsNonWorsening() {
        when(finalSchedule.getTotalInterest()).thenReturn(new BigDecimal("350000.00"));
        when(finalSchedule.getTotalRepayment()).thenReturn(new BigDecimal("10350000.00"));
        when(finalSchedule.getFirstInstallment()).thenReturn(new BigDecimal("1725000.00"));
        when(finalSchedule.getMaximumInstallment()).thenReturn(new BigDecimal("1726000.00"));
        when(contractCreationService.create(
                eq(application), eq(finalSchedule), eq(EXPIRES_AT), eq(ActorType.SYSTEM),
                eq("SYSTEM"), eq("CONTRACT_CREATED_AFTER_NON_WORSENING_TERMS"), eq(NOW)))
                .thenReturn(contract);

        service.prepareAfterApproval(
                application, finalSchedule, EXPIRES_AT, ActorType.SYSTEM, "SYSTEM", NOW);

        verify(application).prepareTermsConfirmation(
                TermsConfirmationStatus.AUTO_AUTHORIZED, "LOAN_TERMS_V1", "c".repeat(64),
                EXPIRES_AT, "SYSTEM", NOW);
        verify(contractCreationService).create(
                application, finalSchedule, EXPIRES_AT, ActorType.SYSTEM, "SYSTEM",
                "CONTRACT_CREATED_AFTER_NON_WORSENING_TERMS", NOW);
    }

    @Test
    void waitsForBorrowerWhenAnyRepaymentObligationIsHigher() {
        when(finalSchedule.getTotalInterest()).thenReturn(new BigDecimal("350000.00"));
        when(finalSchedule.getTotalRepayment()).thenReturn(new BigDecimal("10350000.00"));
        when(finalSchedule.getFirstInstallment()).thenReturn(new BigDecimal("1725000.00"));
        when(finalSchedule.getMaximumInstallment()).thenReturn(new BigDecimal("1734000.00"));

        service.prepareAfterApproval(
                application, finalSchedule, EXPIRES_AT, ActorType.ADMIN, "ADMIN-001", NOW);

        verify(application).prepareTermsConfirmation(
                TermsConfirmationStatus.PENDING, "LOAN_TERMS_V1", "c".repeat(64),
                EXPIRES_AT, "ADMIN-001", NOW);
        verify(contractCreationService, never()).create(
                any(), any(), any(), any(), any(), any(), any());
    }
}
