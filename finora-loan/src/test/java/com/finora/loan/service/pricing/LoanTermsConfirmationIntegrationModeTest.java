package com.finora.loan.service.pricing;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.application.impl.LoanTermsConfirmationServiceImpl;
import com.finora.loan.service.funding.LoanFundingRequestService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanTermsConfirmationIntegrationModeTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private ScheduleCalculationSnapshotRepository scheduleRepository;
    @Mock private LoanContractRepository contractRepository;
    @Mock private LoanFundingRequestService fundingRequestService;
    @Mock private LoanApplicationMapper mapper;
    @Mock private LoanContractProperties contractProperties;
    @Mock private LoanPricingDisclosureProperties disclosureProperties;
    @Mock private HashingService hashingService;
    @Mock private Clock clock;
    @Mock private LoanApplication application;
    @Mock private ScheduleCalculationSnapshot initialSchedule;
    @Mock private ScheduleCalculationSnapshot finalSchedule;

    private LoanTermsConfirmationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LoanTermsConfirmationServiceImpl(
                applicationRepository, scheduleRepository, contractRepository,
                fundingRequestService, mapper, contractProperties,
                disclosureProperties, hashingService, clock);
        when(clock.instant()).thenReturn(NOW);
        when(application.getId()).thenReturn(10L);
        when(application.getFinalCalculationSnapshotId()).thenReturn(2L);
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("10000000.00"));
        when(applicationRepository.findByApplicationNumberForUpdate("LA-001"))
                .thenReturn(Optional.of(application));
        when(applicationRepository.findByTermsConsentIdempotencyKey("terms-key-1"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findByApplicationIdAndPurpose(10L, ScheduleCalculationPurpose.CONTRACT))
                .thenReturn(Optional.of(finalSchedule));
        when(scheduleRepository.findByApplicationIdAndPurpose(10L, ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .thenReturn(Optional.of(initialSchedule));
        when(finalSchedule.getId()).thenReturn(2L);
        when(finalSchedule.getTotalPrincipal()).thenReturn(new BigDecimal("10000000.00"));
        when(hashingService.sha256(any())).thenReturn("c".repeat(64));
        authenticateBorrower();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptedTermsRequestFundingAndDoNotCreateContract() {
        ConfirmLoanTermsRequest request = new ConfirmLoanTermsRequest(
                7L, "LOAN_TERMS_V1", "d".repeat(64));

        service.accept("LA-001", "terms-key-1", request);

        verify(application).acceptTerms(
                7L, "LOAN_TERMS_V1", "d".repeat(64), "terms-key-1", "c".repeat(64),
                "BORROWER-001", NOW);
        verify(fundingRequestService).request(application, NOW);
    }

    private void authenticateBorrower() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("BORROWER-001")
                .claim("user_id", "BORROWER-001")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_BORROWER")), "BORROWER-001"));
    }
}
