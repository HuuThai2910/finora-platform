package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.TermsConfirmationStatus;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.application.LoanTermsConfirmationService;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanTermsConfirmationServiceImpl implements LoanTermsConfirmationService {

    private static final String BORROWER_ACCEPTED_REASON = "CONTRACT_CREATED_AFTER_TERMS_ACCEPTANCE";

    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final LoanContractRepository contractRepository;
    private final LoanContractCreationService contractCreationService;
    private final LoanApplicationMapper mapper;
    private final LoanContractProperties contractProperties;
    private final LoanPricingDisclosureProperties disclosureProperties;
    private final HashingService hashingService;
    private final Clock clock;

    @Override
    @Transactional
    public LoanContract prepareAfterApproval(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            Instant termsExpiresAt,
            ActorType actorType,
            String actorId,
            Instant now
    ) {
        if (application.getTermsConfirmationStatus() != null) {
            return contractRepository.findByApplicationId(application.getId()).orElse(null);
        }
        ScheduleCalculationSnapshot initialSchedule = schedule(application, ScheduleCalculationPurpose.SUBMISSION_SCORING);
        validateFinalSchedule(application, finalSchedule);
        TermsEvidence evidence = TermsEvidence.from(
                application, initialSchedule, finalSchedule, contractProperties.termsVersion());
        boolean disclosureAllowsAutomaticContinuation = disclosureProperties.pricingDisclosureVersion()
                .equals(application.getPricingDisclosureVersionSnapshot());
        boolean autoAuthorized = disclosureAllowsAutomaticContinuation
                && isNonWorsening(application, initialSchedule, finalSchedule);
        application.prepareTermsConfirmation(
                autoAuthorized ? TermsConfirmationStatus.AUTO_AUTHORIZED : TermsConfirmationStatus.PENDING,
                contractProperties.termsVersion(),
                hashingService.sha256(evidence),
                termsExpiresAt,
                actorId,
                now
        );
        applicationRepository.saveAndFlush(application);
        if (!autoAuthorized) {
            return null;
        }
        return contractCreationService.create(
                application, finalSchedule, termsExpiresAt, actorType, actorId,
                "CONTRACT_CREATED_AFTER_NON_WORSENING_TERMS", now);
    }

    @Override
    @Transactional
    public LoanApplicationResponse accept(
            String applicationNumber,
            String idempotencyKey,
            ConfirmLoanTermsRequest request
    ) {
        String normalizedKey = normalizeKey(idempotencyKey);
        String requestHash = hashingService.sha256(request);
        LoanApplication application = locked(applicationNumber);
        if (application.isSameTermsConsent(normalizedKey, requestHash)) {
            return response(application);
        }
        rejectReusedKey(application, normalizedKey);
        Instant now = clock.instant();
        String borrowerId = SecurityUtils.getCurrentUserId();
        application.acceptTerms(
                request.applicationVersion(), request.termsVersion(), request.termsHash(),
                normalizedKey, requestHash, borrowerId, now);
        applicationRepository.saveAndFlush(application);
        ScheduleCalculationSnapshot finalSchedule = schedule(application, ScheduleCalculationPurpose.CONTRACT);
        validateFinalSchedule(application, finalSchedule);
        contractCreationService.create(
                application, finalSchedule, now.plus(contractProperties.signatureWindow()),
                ActorType.BORROWER, borrowerId, BORROWER_ACCEPTED_REASON, now);
        return response(application);
    }

    @Override
    @Transactional
    public LoanApplicationResponse decline(
            String applicationNumber,
            String idempotencyKey,
            DeclineLoanTermsRequest request
    ) {
        String normalizedKey = normalizeKey(idempotencyKey);
        String requestHash = hashingService.sha256(request);
        LoanApplication application = locked(applicationNumber);
        if (application.isSameTermsConsent(normalizedKey, requestHash)) {
            return response(application);
        }
        rejectReusedKey(application, normalizedKey);
        String borrowerId = SecurityUtils.getCurrentUserId();
        application.declineTerms(
                request.applicationVersion(), request.termsVersion(), request.termsHash(),
                request.reasonCode().name(), request.reasonDetail(), normalizedKey, requestHash,
                borrowerId, clock.instant());
        applicationRepository.saveAndFlush(application);
        return response(application);
    }

    private boolean isNonWorsening(
            LoanApplication application,
            ScheduleCalculationSnapshot initial,
            ScheduleCalculationSnapshot resolved
    ) {
        return resolved.getTotalPrincipal().compareTo(initial.getTotalPrincipal()) == 0
                && application.getFinalAnnualInterestRate().compareTo(application.getAnnualInterestRateSnapshot()) <= 0
                && notGreater(resolved.getTotalInterest(), initial.getTotalInterest())
                && notGreater(resolved.getTotalFees(), initial.getTotalFees())
                && notGreater(resolved.getTotalPenalties(), initial.getTotalPenalties())
                && notGreater(resolved.getTotalRepayment(), initial.getTotalRepayment())
                && notGreater(resolved.getFirstInstallment(), initial.getFirstInstallment())
                && notGreater(resolved.getMaximumInstallment(), initial.getMaximumInstallment());
    }

    private boolean notGreater(BigDecimal resolved, BigDecimal initial) {
        return resolved.compareTo(initial) <= 0;
    }

    private void validateFinalSchedule(LoanApplication application, ScheduleCalculationSnapshot schedule) {
        if (schedule == null
                || !schedule.getId().equals(application.getFinalCalculationSnapshotId())
                || schedule.getTotalPrincipal().compareTo(application.getRequestedAmount()) != 0) {
            throw LoanBusinessException.conflict(
                    "FINAL_SCHEDULE_MISMATCH",
                    "Lịch trả nợ cuối không khớp exact terms của hồ sơ"
            );
        }
    }

    private ScheduleCalculationSnapshot schedule(LoanApplication application, ScheduleCalculationPurpose purpose) {
        return scheduleRepository.findByApplicationIdAndPurpose(application.getId(), purpose)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
    }

    private LoanApplication locked(String applicationNumber) {
        return applicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private LoanApplicationResponse response(LoanApplication application) {
        return mapper.toResponse(
                application,
                schedule(application, ScheduleCalculationPurpose.SUBMISSION_SCORING),
                schedule(application, ScheduleCalculationPurpose.CONTRACT)
        );
    }

    private void rejectReusedKey(LoanApplication application, String idempotencyKey) {
        if (application.getTermsConsentIdempotencyKey() != null
                || applicationRepository.findByTermsConsentIdempotencyKey(idempotencyKey).isPresent()) {
            throw LoanBusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho phản hồi điều khoản khác"
            );
        }
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw LoanBusinessException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu Idempotency-Key");
        }
        return idempotencyKey.trim();
    }

    /** Payload canonical chỉ tham chiếu immutable snapshot, không sao chép periods vào Application. */
    private record TermsEvidence(
            String applicationNumber,
            String termsVersion,
            BigDecimal requestedAmount,
            Integer requestedTermMonths,
            BigDecimal initialAnnualRate,
            BigDecimal finalAnnualRate,
            String initialScheduleHash,
            String finalScheduleHash,
            BigDecimal initialTotalInterest,
            BigDecimal finalTotalInterest,
            BigDecimal initialTotalRepayment,
            BigDecimal finalTotalRepayment,
            BigDecimal initialMaximumInstallment,
            BigDecimal finalMaximumInstallment
    ) {
        private static TermsEvidence from(
                LoanApplication application,
                ScheduleCalculationSnapshot initial,
                ScheduleCalculationSnapshot resolved,
                String termsVersion
        ) {
            return new TermsEvidence(
                    application.getApplicationNumber(), termsVersion,
                    application.getRequestedAmount(), application.getRequestedTermMonths(),
                    application.getAnnualInterestRateSnapshot(), application.getFinalAnnualInterestRate(),
                    initial.getResponseHash(), resolved.getResponseHash(),
                    initial.getTotalInterest(), resolved.getTotalInterest(),
                    initial.getTotalRepayment(), resolved.getTotalRepayment(),
                    initial.getMaximumInstallment(), resolved.getMaximumInstallment()
            );
        }
    }
}
