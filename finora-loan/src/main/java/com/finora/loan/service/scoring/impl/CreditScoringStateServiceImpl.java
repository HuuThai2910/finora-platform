package com.finora.loan.service.scoring.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.pricing.model.RiskBasedPricingResult;
import com.finora.loan.domain.scoring.entity.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.scoring.enums.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.enums.CreditProfileSource;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.integration.ai.AiCreditIntegrationException;
import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.mapper.scoring.AiCreditScoringMapper;
import com.finora.loan.mapper.scoring.CreditScoringMapping;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.application.LoanTermsConfirmationService;
import com.finora.loan.service.pricing.RiskBasedPricingService;
import com.finora.loan.service.scoring.CreditScoringExecution;
import com.finora.loan.service.scoring.CreditScoringFinalization;
import com.finora.loan.service.scoring.CreditScoringStateService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Các bước ghi database của luồng chấm điểm tín dụng; mỗi bước một transaction local, lời gọi AI và Fineract nằm ngoài.
 * <p>
 * Mỗi lần chuyển trạng thái hồ sơ đều ghi lịch sử trong cùng transaction, với actor hệ thống {@code LOAN_SCORING_WORKER}.
 */
@Service
@RequiredArgsConstructor
public class CreditScoringStateServiceImpl implements CreditScoringStateService {

    private static final String SYSTEM_ACTOR = "LOAN_SCORING_WORKER";
    private static final String CREDIT_PROFILE_POLICY_VERSION = "FINORA_INTERNAL_CREDIT_V1";

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final BorrowerCreditProfileRepository borrowerCreditProfileRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final AiCreditScoringMapper aiCreditScoringMapper;
    private final RiskBasedPricingService riskBasedPricingService;
    private final LoanTermsConfirmationService loanTermsConfirmationService;
    private final LoanContractProperties loanContractProperties;
    private final AiCreditProperties aiCreditProperties;
    private final HashingService hashingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional
    public LoanApplication startEligibility(Long applicationId) {
        LoanApplication application = lockedApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.SUBMITTED) {
            Instant now = Instant.now(clock);
            application.startEligibility(SYSTEM_ACTOR, now);
            loanApplicationRepository.saveAndFlush(application);
            loanApplicationStatusHistoryRepository.save(history(
                    applicationId,
                    LoanApplicationStatus.SUBMITTED,
                    LoanApplicationStatus.ELIGIBILITY_PENDING,
                    "ELIGIBILITY_CHECK_STARTED",
                    null,
                    now
            ));
        }
        return application;
    }

    @Override
    @Transactional
    public Long applyEligibility(BorrowerEligibilityCheck eligibility) {
        LoanApplication application = lockedApplication(eligibility.getApplicationId());
        if (application.getStatus() != LoanApplicationStatus.ELIGIBILITY_PENDING) {
            return application.getLatestCreditAssessmentId();
        }
        Instant now = Instant.now(clock);
        if (eligibility.getEligibilityResult() == EligibilityResult.RETRY_PENDING
                || eligibility.getEligibilityResult() == EligibilityResult.DEPENDENCY_UNAVAILABLE) {
            return null;
        }
        if (eligibility.getEligibilityResult() == EligibilityResult.INELIGIBLE) {
            application.rejectAfterEligibility(SYSTEM_ACTOR, now);
            loanApplicationRepository.saveAndFlush(application);
            loanApplicationStatusHistoryRepository.save(history(
                    application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                    LoanApplicationStatus.REJECTED, eligibility.getReasonCode(), null, now));
            return null;
        }
        if (eligibility.getEligibilityResult() == EligibilityResult.INVALID_PROFILE) {
            application.markEligibilityManualReview(SYSTEM_ACTOR, now);
            loanApplicationRepository.saveAndFlush(application);
            loanApplicationStatusHistoryRepository.save(history(
                    application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                    LoanApplicationStatus.PENDING_REVIEW, eligibility.getReasonCode(), null, now));
            return null;
        }

        // Worker có thể chạy trên nhiều instance; upsert trước rồi đọc giúp projection
        // NO_HISTORY chỉ có đúng một row cho mỗi borrower.
        borrowerCreditProfileRepository.ensureNoHistory(
                application.getBorrowerId(), CreditProfileSource.NO_HISTORY.name(),
                CREDIT_PROFILE_POLICY_VERSION, SYSTEM_ACTOR, now);
        BorrowerCreditProfile creditProfile = borrowerCreditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElseThrow(() -> new IllegalStateException("Không thể khởi tạo hồ sơ tín dụng nội bộ"));
        ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
        CreditScoringMapping input = aiCreditScoringMapper.map(application, eligibility, creditProfile, schedule);
        String logicalKey = application.getId() + ":" + input.inputHash() + ":" + aiCreditProperties.modelVersion();
        CreditScoringAssessment assessment = creditScoringAssessmentRepository.findByLogicalScoringKey(logicalKey)
                .orElse(null);
        if (assessment == null) {
            assessment = CreditScoringAssessment.pending(
                    application.getId(),
                    eligibility.getId(),
                    UUID.randomUUID().toString(),
                    logicalKey,
                    aiCreditProperties.modelVersion(),
                    input.inputJson(),
                    input.sourcesJson(),
                    input.inputHash(),
                    now
            );
            creditScoringAssessmentRepository.saveAndFlush(assessment);
        }
        application.startScoring(assessment.getId(), SYSTEM_ACTOR, now);
        loanApplicationRepository.saveAndFlush(application);
        loanApplicationStatusHistoryRepository.save(history(
                application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                LoanApplicationStatus.SCORING, "CREDIT_SCORING_STARTED", null, now));
        return assessment.getId();
    }

    @Override
    @Transactional
    public CreditScoringExecution startExecution(Long assessmentId) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        Instant now = Instant.now(clock);
        if (assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED
                || assessment.getStatus() == CreditAssessmentStatus.FAILED) {
            return CreditScoringExecution.skip(assessmentId);
        }
        if (assessment.getStatus() == CreditAssessmentStatus.PROCESSING
                && assessment.getUpdatedAt().isAfter(now.minus(aiCreditProperties.processingLease()))) {
            return CreditScoringExecution.skip(assessmentId);
        }
        if (assessment.getStatus() == CreditAssessmentStatus.RETRY_PENDING
                && assessment.getNextRetryAt() != null
                && assessment.getNextRetryAt().isAfter(now)) {
            return CreditScoringExecution.skip(assessmentId);
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        if (application.getStatus() == LoanApplicationStatus.SCORING_RETRY_PENDING) {
            application.resumeScoring(SYSTEM_ACTOR, now);
            loanApplicationRepository.saveAndFlush(application);
            loanApplicationStatusHistoryRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING_RETRY_PENDING,
                    LoanApplicationStatus.SCORING, "CREDIT_SCORING_RETRY_STARTED", null, now));
        }
        if (application.getStatus() != LoanApplicationStatus.SCORING) {
            return CreditScoringExecution.skip(assessmentId);
        }
        assessment.markProcessing(now);
        creditScoringAssessmentRepository.saveAndFlush(assessment);
        return new CreditScoringExecution(
                assessment.getId(), assessment.getRequestId(), deserialize(assessment.getInputSnapshotJson()), true);
    }

    @Override
    @Transactional(readOnly = true)
    public CreditScoringFinalization prepareFinalization(Long assessmentId, AiCreditScoreResponse response) {
        CreditScoringAssessment assessment = creditScoringAssessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit Scoring Assessment", "id", assessmentId));
        if (assessment.getStatus() != CreditAssessmentStatus.PROCESSING) {
            throw new IllegalStateException("Assessment không còn ở trạng thái PROCESSING");
        }
        LoanApplication application = loanApplicationRepository.findById(assessment.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", assessment.getApplicationId()));
        if (response.decision() == com.finora.loan.domain.scoring.enums.AiRecommendation.REJECTED) {
            // Hồ sơ đã bị policy AI từ chối không có điều khoản để chào cho borrower,
            // vì vậy không tính final rate và không gọi Fineract tạo lịch CONTRACT.
            return new CreditScoringFinalization(null, null, null, false);
        }
        RiskBasedPricingResult pricing = riskBasedPricingService.calculate(application, response.creditGrade());
        return new CreditScoringFinalization(
                pricing,
                UUID.randomUUID().toString(),
                new ScheduleCalculationRequest(
                        application.getLoanProductId(),
                        application.getFineractProductIdSnapshot(),
                        application.getRequestedAmount(),
                        application.getRequestedTermMonths(),
                        pricing.finalAnnualInterestRate(),
                        application.getRepaymentMethodSnapshot(),
                        application.getCoreConfigVersionSnapshot(),
                        // Ngày nộp hồ sơ theo ngày nghiệp vụ (zone của clock), cùng lịch ngày với Fineract.
                        application.getSubmittedAt().atZone(clock.getZone()).toLocalDate(),
                        application.getExpectedDisbursementDate()
                ),
                true
        );
    }

    @Override
    @Transactional
    public void complete(
            Long assessmentId,
            AiCreditScoreResponse response,
            CreditScoringFinalization finalization,
            ScheduleCalculationResult scheduleResult
    ) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        if (assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED) {
            return;
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        Instant now = Instant.now(clock);
        StoredAiCreditResponse stored = StoredAiCreditResponse.from(response);
        String responseJson = hashingService.toJson(stored);
        String rejectionSummary = response.rejectionReasons() == null || response.rejectionReasons().isEmpty()
                ? null
                : String.join(", ", response.rejectionReasons());
        assessment.markSucceeded(
                response.modelVersion(), response.pdProbability(), response.riskScore(), response.evaluationScore(),
                response.creditGrade(), null, response.decision(), rejectionSummary,
                responseJson, hashingService.sha256(stored), response.decisionPolicyVersion(), now);
        creditScoringAssessmentRepository.saveAndFlush(assessment);

        ScheduleCalculationSnapshot finalSchedule = null;
        if (finalization.scheduleRequired()) {
            if (scheduleResult == null) {
                throw new IllegalArgumentException("scheduleResult không được để trống khi cần lịch cuối");
            }
            finalSchedule = ScheduleCalculationSnapshot.contract(
                    application.getId(),
                    finalization.scheduleRequestId(),
                    application.getFineractProductIdSnapshot(),
                    scheduleResult.estimatedDisbursementDate(),
                    scheduleResult.requestSnapshotJson(),
                    scheduleResult.periodsSnapshotJson(),
                    scheduleResult.totalPrincipal(),
                    scheduleResult.totalInterest(),
                    scheduleResult.totalFees(),
                    scheduleResult.totalPenalties(),
                    scheduleResult.totalRepayment(),
                    scheduleResult.firstInstallment(),
                    scheduleResult.maximumInstallment(),
                    scheduleResult.responseHash(),
                    scheduleResult.calculationPolicyVersion(),
                    SYSTEM_ACTOR,
                    now
            );
            scheduleCalculationSnapshotRepository.saveAndFlush(finalSchedule);
        }

        LoanApplicationStatus from = application.getStatus();
        application.completeScoring(
                assessmentId,
                response.decision(),
                finalization.pricing(),
                finalSchedule == null ? null : finalSchedule.getId(),
                response.decisionPolicyVersion(),
                SYSTEM_ACTOR,
                now
        );
        // APPROVED còn phải gắn terms evidence trong cùng transaction. Không flush trạng thái
        // trung gian trước terms gate vì DB chỉ chấp nhận APPROVED đã có evidence đầy đủ.
        loanApplicationRepository.save(application);
        String reasonCode = switch (response.decision()) {
            case APPROVED -> "AI_POLICY_AUTO_APPROVED";
            case PENDING_REVIEW -> "AI_POLICY_REQUIRES_REVIEW";
            case REJECTED -> "AI_POLICY_AUTO_REJECTED";
        };
        // Chuyển thẩm định thì ghi lý do chuyển (mã từ AI) vào lịch sử; từ chối thì ghi mã chốt chặn như cũ.
        String historyDetail = response.decision() == com.finora.loan.domain.scoring.enums.AiRecommendation.PENDING_REVIEW
                && response.reviewReasons() != null && !response.reviewReasons().isEmpty()
                ? String.join(", ", response.reviewReasons())
                : rejectionSummary;
        loanApplicationStatusHistoryRepository.save(history(
                application.getId(), from, application.getStatus(), reasonCode, historyDetail, now));

        if (response.decision() == com.finora.loan.domain.scoring.enums.AiRecommendation.APPROVED) {
            loanTermsConfirmationService.prepareAfterApproval(
                    application,
                    finalSchedule,
                    now.plus(loanContractProperties.signatureWindow()),
                    ActorType.SYSTEM,
                    SYSTEM_ACTOR,
                    now
            );
        } else {
            loanApplicationRepository.flush();
        }
    }

    @Override
    @Transactional
    public void fail(Long assessmentId, AiCreditIntegrationException failure) {
        fail(assessmentId, failure.getCode(), failure.getMessage(), failure.isRetryable());
    }

    @Override
    @Transactional
    public void fail(Long assessmentId, String code, String detail, boolean retryable) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        if (assessment.getStatus() != CreditAssessmentStatus.PROCESSING) {
            return;
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        Instant now = Instant.now(clock);
        if (retryable && assessment.getAttemptCount() < aiCreditProperties.maxAttempts()) {
            Instant retryAt = now.plus(aiCreditProperties.retryBackoff().multipliedBy(assessment.getAttemptCount()));
            assessment.markRetryPending(code, detail, retryAt, now);
            application.markScoringRetryPending(SYSTEM_ACTOR, now);
            loanApplicationStatusHistoryRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING,
                    LoanApplicationStatus.SCORING_RETRY_PENDING, code, null, now));
        } else {
            assessment.markFailed(code, detail, now);
            application.markPendingReview(SYSTEM_ACTOR, now);
            loanApplicationStatusHistoryRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING,
                    LoanApplicationStatus.PENDING_REVIEW, "CREDIT_SCORING_MANUAL_REVIEW_REQUIRED", null, now));
        }
        creditScoringAssessmentRepository.saveAndFlush(assessment);
        loanApplicationRepository.saveAndFlush(application);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findApplicationCandidates() {
        return loanApplicationRepository.findIdsByStatusIn(
                List.of(LoanApplicationStatus.SUBMITTED, LoanApplicationStatus.ELIGIBILITY_PENDING),
                PageRequest.of(0, aiCreditProperties.workerBatchSize()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findAssessmentCandidates() {
        Instant now = Instant.now(clock);
        return creditScoringAssessmentRepository.findDueIds(
                CreditAssessmentStatus.PENDING,
                CreditAssessmentStatus.RETRY_PENDING,
                CreditAssessmentStatus.PROCESSING,
                now,
                now.minus(aiCreditProperties.processingLease()),
                PageRequest.of(0, aiCreditProperties.workerBatchSize())
        );
    }

    private LoanApplication lockedApplication(Long id) {
        return loanApplicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Application", "id", id));
    }

    private CreditScoringAssessment lockedAssessment(Long id) {
        return creditScoringAssessmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Credit Scoring Assessment", "id", id));
    }

    private AiCreditScoreRequest deserialize(String json) {
        try {
            return objectMapper.readValue(json, AiCreditScoreRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Input snapshot đã lưu không đọc lại được", exception);
        }
    }

    private LoanApplicationStatusHistory history(
            Long applicationId,
            LoanApplicationStatus from,
            LoanApplicationStatus to,
            String reasonCode,
            String reasonDetail,
            Instant now
    ) {
        return LoanApplicationStatusHistory.create(
                applicationId, from, to, reasonCode, reasonDetail,
                ActorType.SYSTEM, SYSTEM_ACTOR, now);
    }
}
