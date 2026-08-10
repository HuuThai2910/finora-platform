package com.finora.loan.service.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanApplicationStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
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
import com.finora.loan.mapper.scoring.AiCreditScoringMapper;
import com.finora.loan.mapper.scoring.CreditScoringMapping;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
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
    private static final String DECISION_POLICY_VERSION = "SCORING_MANUAL_REVIEW_V1";
    private static final String CREDIT_PROFILE_POLICY_VERSION = "FINORA_INTERNAL_CREDIT_V1";

    private final LoanApplicationRepository applicationRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final BorrowerCreditProfileRepository creditProfileRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final AiCreditScoringMapper inputMapper;
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
        ScheduleCalculationSnapshot schedule = scheduleRepository.findByApplicationId(application.getId())
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

    /** Lưu output allowlist và Application transition nguyên tử; suggested_rate đã bị loại trước boundary này. */
    @Transactional
    public void complete(Long assessmentId, AiCreditScoreResponse response) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        if (assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED) {
            return;
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        Instant now = Instant.now(clock);
        StoredAiCreditResponse stored = StoredAiCreditResponse.from(response);
        String responseJson = hashingService.toJson(stored);
        assessment.markSucceeded(
                response.modelVersion(), response.pdProbability(), response.riskScore(), response.evaluationScore(),
                response.creditGrade(), response.suggestedLimit(), response.decision(), response.rejectionReason(),
                responseJson, hashingService.sha256(stored), DECISION_POLICY_VERSION, now);
        assessmentRepository.saveAndFlush(assessment);
        LoanApplicationStatus from = application.getStatus();
        application.markPendingReview(SYSTEM_ACTOR, now);
        applicationRepository.saveAndFlush(application);
        historyRepository.save(history(
                application.getId(), from, LoanApplicationStatus.PENDING_REVIEW,
                "CREDIT_SCORING_SUCCEEDED", null, now));
    }

    /** Retry chỉ áp dụng lỗi tạm thời; hết lượt vẫn chuyển manual review và không tạo điểm mặc định. */
    @Transactional
    public void fail(Long assessmentId, AiCreditIntegrationException failure) {
        CreditScoringAssessment assessment = lockedAssessment(assessmentId);
        if (assessment.getStatus() != CreditAssessmentStatus.PROCESSING) {
            return;
        }
        LoanApplication application = lockedApplication(assessment.getApplicationId());
        Instant now = Instant.now(clock);
        if (failure.isRetryable() && assessment.getAttemptCount() < properties.maxAttempts()) {
            Instant retryAt = now.plus(properties.retryBackoff().multipliedBy(assessment.getAttemptCount()));
            assessment.markRetryPending(failure.getCode(), failure.getMessage(), retryAt, now);
            application.markScoringRetryPending(SYSTEM_ACTOR, now);
            historyRepository.save(history(
                    application.getId(), LoanApplicationStatus.SCORING,
                    LoanApplicationStatus.SCORING_RETRY_PENDING, failure.getCode(), null, now));
        } else {
            assessment.markFailed(failure.getCode(), failure.getMessage(), now);
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
