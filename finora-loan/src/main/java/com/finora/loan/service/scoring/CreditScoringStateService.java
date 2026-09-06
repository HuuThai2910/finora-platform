package com.finora.loan.service.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanApplicationStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.domain.scoring.CreditProfileSource;
import com.finora.loan.domain.scoring.EligibilityResult;
import com.finora.loan.integration.ai.client.AiCreditIntegrationException;
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
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.domain.pricing.RiskBasedPricingResult;
import com.finora.loan.service.pricing.RiskBasedPricingService;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditScoringStateService {

    private static final String SYSTEM_ACTOR = "LOAN_SCORING_WORKER";
    private static final String CREDIT_PROFILE_POLICY_VERSION = "FINORA_INTERNAL_CREDIT_V1";

    private final LoanApplicationRepository applicationRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final BorrowerCreditProfileRepository creditProfileRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final AiCreditScoringMapper inputMapper;
    private final RiskBasedPricingService pricingService;
    private final LoanContractCreationService contractCreationService;
    private final LoanContractProperties contractProperties;
    private final AiCreditProperties properties;
    private final HashingService hashingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Commit ELIGIBILITY_PENDING trước khi gọi profile provider để restart còn thấy việc chưa hoàn tất. */
    @Transactional
    public LoanApplication startEligibility(Long applicationId) {
        LoanApplication application = lockedApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.SUBMITTED) {
            Instant now = Instant.now(clock);
            application.startEligibility(SYSTEM_ACTOR, now);
            applicationRepository.saveAndFlush(application);
            historyRepository.save(history(
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

    /**
     * Eligible mới tạo immutable input/assessment. Việc này chỉ đụng DB local và kết thúc trước HTTP AI.
     */
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
            applicationRepository.saveAndFlush(application);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                    LoanApplicationStatus.REJECTED, eligibility.getReasonCode(), null, now));
            return null;
        }
        if (eligibility.getEligibilityResult() == EligibilityResult.INVALID_PROFILE) {
            application.markEligibilityManualReview(SYSTEM_ACTOR, now);
            applicationRepository.saveAndFlush(application);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                    LoanApplicationStatus.PENDING_REVIEW, eligibility.getReasonCode(), null, now));
            return null;
        }

        // Worker có thể chạy trên nhiều instance; upsert trước rồi đọc giúp projection
        // NO_HISTORY chỉ có đúng một row cho mỗi borrower.
        creditProfileRepository.ensureNoHistory(
                application.getBorrowerId(), CreditProfileSource.NO_HISTORY.name(),
                CREDIT_PROFILE_POLICY_VERSION, SYSTEM_ACTOR, now);
        BorrowerCreditProfile creditProfile = creditProfileRepository.findByBorrowerId(application.getBorrowerId())
                .orElseThrow(() -> new IllegalStateException("Không thể khởi tạo hồ sơ tín dụng nội bộ"));
        ScheduleCalculationSnapshot schedule = scheduleRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
        CreditScoringMapping input = inputMapper.map(application, eligibility, creditProfile, schedule);
        String logicalKey = application.getId() + ":" + input.inputHash() + ":" + properties.modelVersion();
        CreditScoringAssessment assessment = assessmentRepository.findByLogicalScoringKey(logicalKey).orElse(null);
        if (assessment == null) {
            assessment = CreditScoringAssessment.pending(
                    application.getId(),
                    eligibility.getId(),
                    UUID.randomUUID().toString(),
                    logicalKey,
                    properties.modelVersion(),
                    input.inputJson(),
                    input.sourcesJson(),
                    input.inputHash(),
                    now
            );
            assessmentRepository.saveAndFlush(assessment);
        }
        application.startScoring(assessment.getId(), SYSTEM_ACTOR, now);
        applicationRepository.saveAndFlush(application);
        historyRepository.save(history(
                application.getId(), LoanApplicationStatus.ELIGIBILITY_PENDING,
                LoanApplicationStatus.SCORING, "CREDIT_SCORING_STARTED", null, now));
        return assessment.getId();
    }

    /** Lấy lease ngắn trong DB rồi đóng transaction; HTTP AI xảy ra sau khi method trả về. */
    @Transactional
    public CreditScoringExecution startExecution(Long assessmentId) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        Instant now = Instant.now(clock);
        if (assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED
                || assessment.getStatus() == CreditAssessmentStatus.FAILED) {
            return CreditScoringExecution.skip(assessmentId);
        }
        if (assessment.getStatus() == CreditAssessmentStatus.PROCESSING
                && assessment.getUpdatedAt().isAfter(now.minus(properties.processingLease()))) {
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
            applicationRepository.saveAndFlush(application);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING_RETRY_PENDING,
                    LoanApplicationStatus.SCORING, "CREDIT_SCORING_RETRY_STARTED", null, now));
        }
        if (application.getStatus() != LoanApplicationStatus.SCORING) {
            return CreditScoringExecution.skip(assessmentId);
        }
        assessment.markProcessing(now);
        assessmentRepository.saveAndFlush(assessment);
        return new CreditScoringExecution(
                assessment.getId(), assessment.getRequestId(), deserialize(assessment.getInputSnapshotJson()), true);
    }

    /**
     * Chuẩn bị lãi suất và request lịch cuối bằng scalar; Fineract sẽ được gọi sau khi transaction đóng.
     */
    @Transactional(readOnly = true)
    public CreditScoringFinalization prepareFinalization(Long assessmentId, AiCreditScoreResponse response) {
        CreditScoringAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit Scoring Assessment", "id", assessmentId));
        if (assessment.getStatus() != CreditAssessmentStatus.PROCESSING) {
            throw new IllegalStateException("Assessment không còn ở trạng thái PROCESSING");
        }
        LoanApplication application = applicationRepository.findById(assessment.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", assessment.getApplicationId()));
        if (response.decision() == com.finora.loan.domain.scoring.AiRecommendation.REJECTED) {
            // Hồ sơ đã bị policy AI từ chối không có điều khoản để chào cho borrower,
            // vì vậy không tính final rate và không gọi Fineract tạo lịch CONTRACT.
            return new CreditScoringFinalization(null, null, null, false);
        }
        RiskBasedPricingResult pricing = pricingService.calculate(application, response.creditGrade());
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
                        application.getSubmittedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        application.getExpectedDisbursementDate()
                ),
                true
        );
    }

    /** Lưu output v17, final schedule, trạng thái và hợp đồng auto-approve trong một transaction local. */
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
        assessmentRepository.saveAndFlush(assessment);

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
            scheduleRepository.saveAndFlush(finalSchedule);
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
        applicationRepository.saveAndFlush(application);
        String reasonCode = switch (response.decision()) {
            case APPROVED -> "AI_POLICY_AUTO_APPROVED";
            case PENDING_REVIEW -> "AI_POLICY_REQUIRES_REVIEW";
            case REJECTED -> "AI_POLICY_AUTO_REJECTED";
        };
        historyRepository.save(history(
                application.getId(), from, application.getStatus(), reasonCode, rejectionSummary, now));

        if (response.decision() == com.finora.loan.domain.scoring.AiRecommendation.APPROVED) {
            contractCreationService.create(
                    application,
                    finalSchedule,
                    now.plus(contractProperties.signatureWindow()),
                    ActorType.SYSTEM,
                    SYSTEM_ACTOR,
                    "CONTRACT_CREATED_AFTER_AUTO_APPROVAL",
                    now
            );
        }
    }

    /** Retry chỉ áp dụng lỗi tạm thời; hết lượt vẫn chuyển manual review và không tạo điểm mặc định. */
    @Transactional
    public void fail(Long assessmentId, AiCreditIntegrationException failure) {
        fail(assessmentId, failure.getCode(), failure.getMessage(), failure.isRetryable());
    }

    /** Dùng chung failure state cho AI và bước tính final schedule ở Fineract. */
    @Transactional
    public void fail(Long assessmentId, String code, String detail, boolean retryable) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        if (assessment.getStatus() != CreditAssessmentStatus.PROCESSING) {
            return;
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        Instant now = Instant.now(clock);
        if (retryable && assessment.getAttemptCount() < properties.maxAttempts()) {
            Instant retryAt = now.plus(properties.retryBackoff().multipliedBy(assessment.getAttemptCount()));
            assessment.markRetryPending(code, detail, retryAt, now);
            application.markScoringRetryPending(SYSTEM_ACTOR, now);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING,
                    LoanApplicationStatus.SCORING_RETRY_PENDING, code, null, now));
        } else {
            assessment.markFailed(code, detail, now);
            application.markPendingReview(SYSTEM_ACTOR, now);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING,
                    LoanApplicationStatus.PENDING_REVIEW, "CREDIT_SCORING_MANUAL_REVIEW_REQUIRED", null, now));
        }
        assessmentRepository.saveAndFlush(assessment);
        applicationRepository.saveAndFlush(application);
    }

    @Transactional(readOnly = true)
    /** Lấy batch hữu hạn hồ sơ mới hoặc chưa xong eligibility, không quét toàn bảng mỗi chu kỳ. */
    public List<Long> findApplicationCandidates() {
        return applicationRepository.findIdsByStatusIn(
                List.of(LoanApplicationStatus.SUBMITTED, LoanApplicationStatus.ELIGIBILITY_PENDING),
                PageRequest.of(0, properties.workerBatchSize()));
    }

    @Transactional(readOnly = true)
    /** Lấy assessment đến hạn hoặc PROCESSING mất lease để worker khác có thể tiếp quản an toàn. */
    public List<Long> findAssessmentCandidates() {
        Instant now = Instant.now(clock);
        return assessmentRepository.findDueIds(
                CreditAssessmentStatus.PENDING,
                CreditAssessmentStatus.RETRY_PENDING,
                CreditAssessmentStatus.PROCESSING,
                now,
                now.minus(properties.processingLease()),
                PageRequest.of(0, properties.workerBatchSize())
        );
    }

    private LoanApplication lockedApplication(Long id) {
        return applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Application", "id", id));
    }

    private CreditScoringAssessment lockedAssessment(Long id) {
        return assessmentRepository.findByIdForUpdate(id)
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
