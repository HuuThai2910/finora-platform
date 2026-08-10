package com.finora.loan.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.ActorType;
import com.finora.loan.domain.CreditAssessmentStatus;
import com.finora.loan.domain.CreditScoringAssessment;
import com.finora.loan.domain.CreditScoringRetryRequest;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.LoanApplicationStatus;
import com.finora.loan.domain.LoanApplicationStatusHistory;
import com.finora.loan.dto.request.ScoringRetryRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.CreditScoringAssessmentRepository;
import com.finora.loan.repository.CreditScoringRetryRequestRepository;
import com.finora.loan.repository.LoanApplicationRepository;
import com.finora.loan.repository.LoanApplicationStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CreditScoringAdminStateService {

    private final LoanApplicationRepository applicationRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final CreditScoringRetryRequestRepository retryRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final HashingService hashingService;
    private final MockCurrentUserProvider currentUser;
    private final Clock clock;

    /** Idempotency record, reopen assessment và Application transition cùng một transaction. */
    @Transactional
    public CreditScoringAssessment retry(
            String applicationNumber,
            String normalizedKey,
            ScoringRetryRequest request
    ) {
        String requestHash = hashingService.sha256(request);
        CreditScoringRetryRequest existingRetry = retryRepository.findByIdempotencyKey(normalizedKey).orElse(null);
        if (existingRetry != null) {
            if (!existingRetry.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key đã được dùng cho nội dung retry khác");
            }
            return assessmentRepository.findById(existingRetry.getAssessmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Credit Scoring Assessment", "id", existingRetry.getAssessmentId()));
        }

        LoanApplication application = applicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
        if (application.getLatestCreditAssessmentId() == null) {
            throw LoanBusinessException.conflict(
                    "CREDIT_ASSESSMENT_NOT_FOUND", "Hồ sơ chưa có assessment để retry");
        }
        CreditScoringAssessment assessment = assessmentRepository
                .findByIdForUpdate(application.getLatestCreditAssessmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", application.getLatestCreditAssessmentId()));
        if (assessment.getStatus() != CreditAssessmentStatus.FAILED) {
            throw LoanBusinessException.conflict(
                    "CREDIT_ASSESSMENT_RETRY_NOT_ALLOWED", "Chỉ assessment FAILED mới được retry thủ công");
        }
        Instant now = Instant.now(clock);
        String actorId = currentUser.adminUserId();
        retryRepository.saveAndFlush(CreditScoringRetryRequest.create(
                application.getId(), assessment.getId(), normalizedKey, requestHash, actorId, now));
        assessment.reopen(now);
        application.reopenScoring(request.version(), assessment.getId(), actorId, now);
        assessmentRepository.saveAndFlush(assessment);
        applicationRepository.saveAndFlush(application);
        historyRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW,
                LoanApplicationStatus.SCORING_RETRY_PENDING, "CREDIT_SCORING_MANUAL_RETRY_REQUESTED",
                null, ActorType.ADMIN, actorId, now));
        return assessment;
    }
}
