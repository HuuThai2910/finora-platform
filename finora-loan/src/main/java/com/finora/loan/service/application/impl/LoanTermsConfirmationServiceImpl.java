package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.service.application.LoanTermsConfirmationService;
import com.finora.loan.service.funding.LoanFundingRequestService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Xác nhận điều khoản sau duyệt: chuẩn bị bằng chứng điều khoản cuối và nhận chấp nhận/từ chối của người vay.
 *
 * <p>Điều khoản cuối không bất lợi hơn lúc nộp và công bố lãi suất chưa đổi thì được tự động chấp nhận và mở gọi vốn
 * ngay; ngược lại hồ sơ chờ người vay phản hồi.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanTermsConfirmationServiceImpl implements LoanTermsConfirmationService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final LoanContractRepository loanContractRepository;
    private final LoanFundingRequestService loanFundingRequestService;
    private final LoanApplicationMapper loanApplicationMapper;
    private final LoanContractProperties loanContractProperties;
    private final LoanPricingDisclosureProperties loanPricingDisclosureProperties;
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
            return loanContractRepository.findByApplicationId(application.getId()).orElse(null);
        }
        ScheduleCalculationSnapshot initialSchedule = schedule(application, ScheduleCalculationPurpose.SUBMISSION_SCORING);
        validateFinalSchedule(application, finalSchedule);
        TermsEvidence evidence = TermsEvidence.from(
                application, initialSchedule, finalSchedule, loanContractProperties.termsVersion());
        boolean disclosureAllowsAutomaticContinuation = loanPricingDisclosureProperties.pricingDisclosureVersion()
                .equals(application.getPricingDisclosureVersionSnapshot());
        boolean autoAuthorized = disclosureAllowsAutomaticContinuation
                && isNonWorsening(application, initialSchedule, finalSchedule);
        application.prepareTermsConfirmation(
                autoAuthorized ? TermsConfirmationStatus.AUTO_AUTHORIZED : TermsConfirmationStatus.PENDING,
                loanContractProperties.termsVersion(),
                hashingService.sha256(evidence),
                termsExpiresAt,
                actorId,
                now
        );
        loanApplicationRepository.saveAndFlush(application);
        if (!autoAuthorized) {
            return null;
        }
        loanFundingRequestService.request(application, now);
        return null;
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
        loanApplicationRepository.saveAndFlush(application);
        ScheduleCalculationSnapshot finalSchedule = schedule(application, ScheduleCalculationPurpose.CONTRACT);
        validateFinalSchedule(application, finalSchedule);
        loanFundingRequestService.request(application, now);
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
        loanApplicationRepository.saveAndFlush(application);
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
        return scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(application.getId(), purpose)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
    }

    private LoanApplication locked(String applicationNumber) {
        return loanApplicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private LoanApplicationResponse response(LoanApplication application) {
        return loanApplicationMapper.toResponse(
                application,
                schedule(application, ScheduleCalculationPurpose.SUBMISSION_SCORING),
                schedule(application, ScheduleCalculationPurpose.CONTRACT)
        );
    }

    private void rejectReusedKey(LoanApplication application, String idempotencyKey) {
        if (application.getTermsConsentIdempotencyKey() != null
                || loanApplicationRepository.findByTermsConsentIdempotencyKey(idempotencyKey).isPresent()) {
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
