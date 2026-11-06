package com.finora.loan.service.scoring.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.scoring.entity.CreditScoringRetryRequest;
import com.finora.loan.domain.scoring.enums.CreditAssessmentStatus;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.repository.scoring.CreditScoringRetryRequestRepository;
import com.finora.loan.service.scoring.CreditScoringAdminStateService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Thao tác quản trị trên assessment chấm điểm: mở lại assessment {@code FAILED} để chấm lại.
 * <p>
 * Idempotent theo {@code Idempotency-Key}: gửi lại cùng nội dung trả assessment cũ, khác nội dung thì 409
 * {@code IDEMPOTENCY_KEY_REUSED}.
 */
@Service
@RequiredArgsConstructor
public class CreditScoringAdminStateServiceImpl implements CreditScoringAdminStateService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final CreditScoringRetryRequestRepository creditScoringRetryRequestRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final HashingService hashingService;
    private final Clock clock;

    @Override
    @Transactional
    public CreditScoringAssessment retry(
            String applicationNumber,
            String normalizedKey,
            ScoringRetryRequest request,
            String actorId
    ) {
        String requestHash = hashingService.sha256(request);
        CreditScoringRetryRequest existingRetry = creditScoringRetryRequestRepository
                .findByIdempotencyKey(normalizedKey).orElse(null);
        if (existingRetry != null) {
            if (!existingRetry.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key đã được dùng cho nội dung retry khác");
            }
            return creditScoringAssessmentRepository.findById(existingRetry.getAssessmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Credit Scoring Assessment", "id", existingRetry.getAssessmentId()));
        }

        LoanApplication application = loanApplicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
        if (application.getLatestCreditAssessmentId() == null) {
            throw LoanBusinessException.conflict(
                    "CREDIT_ASSESSMENT_NOT_FOUND", "Hồ sơ chưa có assessment để retry");
        }
        CreditScoringAssessment assessment = creditScoringAssessmentRepository
                .findByIdForUpdate(application.getLatestCreditAssessmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", application.getLatestCreditAssessmentId()));
        if (assessment.getStatus() != CreditAssessmentStatus.FAILED) {
            throw LoanBusinessException.conflict(
                    "CREDIT_ASSESSMENT_RETRY_NOT_ALLOWED", "Chỉ assessment FAILED mới được retry thủ công");
        }
        Instant now = Instant.now(clock);
        creditScoringRetryRequestRepository.saveAndFlush(CreditScoringRetryRequest.create(
                application.getId(), assessment.getId(), normalizedKey, requestHash, actorId, now));
        assessment.reopen(now);
        application.reopenScoring(request.version(), assessment.getId(), actorId, now);
        creditScoringAssessmentRepository.saveAndFlush(assessment);
        loanApplicationRepository.saveAndFlush(application);
        loanApplicationStatusHistoryRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW,
                LoanApplicationStatus.SCORING_RETRY_PENDING, "CREDIT_SCORING_MANUAL_RETRY_REQUESTED",
                null, ActorType.ADMIN, actorId, now));
        return assessment;
    }
}
