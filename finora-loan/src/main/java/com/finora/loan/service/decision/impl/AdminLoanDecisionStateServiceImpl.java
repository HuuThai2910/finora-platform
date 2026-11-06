package com.finora.loan.service.decision.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.scoring.enums.CreditAssessmentStatus;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.application.LoanTermsConfirmationService;
import com.finora.loan.service.decision.AdminDecisionResult;
import com.finora.loan.service.decision.AdminLoanDecisionStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Các bước transaction của quyết định duyệt/từ chối hồ sơ của admin, kèm đối soát idempotency.
 *
 * <p>Quyết định, history và bước xác nhận điều khoản sau duyệt cùng commit hoặc cùng rollback dưới khóa hồ sơ.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLoanDecisionStateServiceImpl implements AdminLoanDecisionStateService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final LoanContractRepository loanContractRepository;
    private final LoanTermsConfirmationService loanTermsConfirmationService;
    private final LoanContractProperties loanContractProperties;
    private final Clock clock;

    @Override
    @Transactional
    public AdminDecisionResult approve(
            String applicationNumber,
            String idempotencyKey,
            String requestHash,
            ApproveLoanApplicationRequest request,
            String actorId
    ) {
        AdminDecisionResult duplicate = duplicateDecision(applicationNumber, idempotencyKey, requestHash);
        if (duplicate != null) {
            return duplicate;
        }
        if (!request.decisionReasonCode().isApproval()) {
            throw LoanBusinessException.badRequest(
                    "ADMIN_DECISION_REASON_INVALID",
                    "Approve chỉ chấp nhận reason code POLICY_APPROVED"
            );
        }
        LoanApplication application = lockedApplication(applicationNumber);
        Instant now = clock.instant();
        CreditScoringAssessment assessment = creditScoringAssessmentRepository
                .findByIdAndApplicationId(request.assessmentId(), application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", request.assessmentId()));
        if (assessment.getStatus() != CreditAssessmentStatus.SUCCEEDED
                || !request.assessmentId().equals(application.getLatestCreditAssessmentId())) {
            throw LoanBusinessException.conflict(
                    "ASSESSMENT_NOT_ELIGIBLE_FOR_DECISION",
                    "Chỉ assessment SUCCEEDED mới nhất mới được dùng để duyệt"
            );
        }
        ScheduleCalculationSnapshot schedule = schedule(application);
        Instant expiresAt = expiry(request.contractExpiresAt(), now);
        application.approveByAdmin(
                request.applicationVersion(), request.assessmentId(), request.decisionReasonCode().name(),
                request.decisionReasonDetail(), loanContractProperties.decisionPolicyVersion(), idempotencyKey,
                requestHash, actorId, now);
        // Terms gate bên dưới sẽ flush APPROVED cùng evidence; tránh commit/flush một trạng thái
        // APPROVED chưa xác định AUTO_AUTHORIZED hay PENDING.
        loanApplicationRepository.save(application);
        loanApplicationStatusHistoryRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW, LoanApplicationStatus.APPROVED,
                request.decisionReasonCode().name(), request.decisionReasonDetail(),
                ActorType.ADMIN, actorId, now));

        LoanContract contract = loanTermsConfirmationService.prepareAfterApproval(
                application, schedule, expiresAt, ActorType.ADMIN, actorId, now);
        return new AdminDecisionResult(application, contract);
    }

    @Override
    @Transactional
    public AdminDecisionResult reject(
            String applicationNumber,
            String idempotencyKey,
            String requestHash,
            RejectLoanApplicationRequest request,
            String actorId
    ) {
        AdminDecisionResult duplicate = duplicateDecision(applicationNumber, idempotencyKey, requestHash);
        if (duplicate != null) {
            return duplicate;
        }
        if (request.reasonCode().isApproval()) {
            throw LoanBusinessException.badRequest(
                    "ADMIN_DECISION_REASON_INVALID",
                    "Reject không chấp nhận reason code POLICY_APPROVED"
            );
        }
        LoanApplication application = lockedApplication(applicationNumber);
        validateRejectAssessment(application, request.assessmentId());
        Instant now = clock.instant();
        application.rejectByAdmin(
                request.applicationVersion(), request.assessmentId(), request.reasonCode().name(),
                request.reasonDetail(), loanContractProperties.decisionPolicyVersion(), idempotencyKey,
                requestHash, actorId, now);
        loanApplicationRepository.saveAndFlush(application);
        loanApplicationStatusHistoryRepository.saveAndFlush(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW, LoanApplicationStatus.REJECTED,
                request.reasonCode().name(), request.reasonDetail(), ActorType.ADMIN, actorId, now));
        return new AdminDecisionResult(application, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDecisionResult findCommittedByKey(
            String applicationNumber,
            String idempotencyKey,
            String requestHash
    ) {
        // Dùng lại cùng một quy tắc để race và request tuần tự đều trả đúng IDEMPOTENCY_KEY_REUSED.
        return duplicateDecision(applicationNumber, idempotencyKey, requestHash);
    }

    private AdminDecisionResult duplicateDecision(
            String applicationNumber,
            String idempotencyKey,
            String requestHash
    ) {
        LoanApplication existing = loanApplicationRepository
                .findByAdminDecisionIdempotencyKey(idempotencyKey)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.getApplicationNumber().equals(applicationNumber)
                || !existing.isSameAdminDecision(idempotencyKey, requestHash)) {
            throw LoanBusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng cho một quyết định khác"
            );
        }
        LoanContract contract = loanContractRepository.findByApplicationId(existing.getId()).orElse(null);
        return new AdminDecisionResult(existing, contract);
    }

    private void validateRejectAssessment(LoanApplication application, Long assessmentId) {
        Long latestId = application.getLatestCreditAssessmentId();
        if (latestId == null && assessmentId == null) {
            return;
        }
        if (assessmentId == null || !assessmentId.equals(latestId)) {
            throw LoanBusinessException.conflict(
                    "ASSESSMENT_NOT_ELIGIBLE_FOR_DECISION",
                    "Reject phải tham chiếu assessment mới nhất nếu hồ sơ đã có assessment"
            );
        }
        creditScoringAssessmentRepository.findByIdAndApplicationId(assessmentId, application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", assessmentId));
    }

    private LoanApplication lockedApplication(String applicationNumber) {
        return loanApplicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private ScheduleCalculationSnapshot schedule(LoanApplication application) {
        ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.CONTRACT)
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "FINAL_SCHEDULE_REQUIRED",
                        "Hồ sơ chưa có lịch trả nợ cuối để tạo Contract"
                ));
        if (!schedule.getId().equals(application.getFinalCalculationSnapshotId())
                || schedule.getTotalPrincipal().compareTo(application.getRequestedAmount()) != 0) {
            throw LoanBusinessException.conflict(
                    "FINAL_SCHEDULE_MISMATCH",
                    "Lịch trả nợ cuối không khớp exact terms của hồ sơ"
            );
        }
        return schedule;
    }

    private Instant expiry(Instant requested, Instant now) {
        Instant resolved = requested == null ? now.plus(loanContractProperties.signatureWindow()) : requested;
        if (!resolved.isAfter(now) || resolved.isAfter(now.plus(loanContractProperties.maximumSignatureWindow()))) {
            throw LoanBusinessException.badRequest(
                    "CONTRACT_EXPIRY_INVALID",
                    "Hạn ký phải ở tương lai và không vượt cửa sổ tối đa"
            );
        }
        return resolved;
    }

}
