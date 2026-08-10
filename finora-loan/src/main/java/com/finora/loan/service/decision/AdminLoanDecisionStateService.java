package com.finora.loan.service.decision;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanApplicationStatusHistory;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.contract.LoanContractTerms;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.decision.AdminDecisionReasonCode;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractNumberGenerator;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminLoanDecisionStateService {

    private final LoanApplicationRepository applicationRepository;
    private final LoanApplicationStatusHistoryRepository applicationHistoryRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final LoanContractRepository contractRepository;
    private final LoanContractStatusHistoryRepository contractHistoryRepository;
    private final ContractNumberGenerator contractNumberGenerator;
    private final ContractDocumentRenderer documentRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties properties;
    private final Clock clock;

    /** Toàn bộ quyết định, Contract và hai history row cùng commit hoặc cùng rollback. */
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
        CreditScoringAssessment assessment = assessmentRepository
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
        String contractNumber = contractNumberGenerator.next();
        String documentContent = documentRenderer.render(
                contractNumber, application, schedule, properties.termsVersion(),
                properties.documentVersion(), expiresAt);
        String documentHash = hashingService.sha256Text(documentContent);

        application.approveByAdmin(
                request.applicationVersion(), request.assessmentId(), request.decisionReasonCode().name(),
                request.decisionReasonDetail(), properties.decisionPolicyVersion(), idempotencyKey,
                requestHash, actorId, now);
        applicationRepository.saveAndFlush(application);
        applicationHistoryRepository.save(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW, LoanApplicationStatus.APPROVED,
                request.decisionReasonCode().name(), request.decisionReasonDetail(),
                ActorType.ADMIN, actorId, now));

        LoanContract contract = LoanContract.create(
                contractNumber, application.getId(), application.getBorrowerId(), terms(application, schedule),
                properties.termsVersion(), properties.documentVersion(), documentContent, documentHash,
                expiresAt, actorId, now);
        contractRepository.saveAndFlush(contract);
        contractHistoryRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), null, LoanContractStatus.PENDING_SIGNATURE, "CONTRACT_CREATED",
                ActorType.ADMIN, actorId, now, TraceContext.currentTraceIdOrCreate()));
        return new AdminDecisionResult(application, contract);
    }

    /** Admin reject không tạo Contract; history vẫn commit cùng decision evidence. */
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
                request.reasonDetail(), properties.decisionPolicyVersion(), idempotencyKey,
                requestHash, actorId, now);
        applicationRepository.saveAndFlush(application);
        applicationHistoryRepository.saveAndFlush(LoanApplicationStatusHistory.create(
                application.getId(), LoanApplicationStatus.PENDING_REVIEW, LoanApplicationStatus.REJECTED,
                request.reasonCode().name(), request.reasonDetail(), ActorType.ADMIN, actorId, now));
        return new AdminDecisionResult(application, null);
    }

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
        LoanApplication existing = applicationRepository
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
        LoanContract contract = contractRepository.findByApplicationId(existing.getId()).orElse(null);
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
        assessmentRepository.findByIdAndApplicationId(assessmentId, application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", assessmentId));
    }

    private LoanApplication lockedApplication(String applicationNumber) {
        return applicationRepository.findByApplicationNumberForUpdate(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private ScheduleCalculationSnapshot schedule(LoanApplication application) {
        ScheduleCalculationSnapshot schedule = scheduleRepository.findByApplicationId(application.getId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "SUBMISSION_SCHEDULE_REQUIRED",
                        "Hồ sơ chưa có schedule submission để tạo Contract"
                ));
        if (!schedule.getId().equals(application.getSubmissionCalculationSnapshotId())
                || schedule.getTotalPrincipal().compareTo(application.getRequestedAmount()) != 0) {
            throw LoanBusinessException.conflict(
                    "SUBMISSION_SCHEDULE_MISMATCH",
                    "Schedule submission không khớp exact terms của hồ sơ"
            );
        }
        return schedule;
    }

    private Instant expiry(Instant requested, Instant now) {
        Instant resolved = requested == null ? now.plus(properties.signatureWindow()) : requested;
        if (!resolved.isAfter(now) || resolved.isAfter(now.plus(properties.maximumSignatureWindow()))) {
            throw LoanBusinessException.badRequest(
                    "CONTRACT_EXPIRY_INVALID",
                    "Hạn ký phải ở tương lai và không vượt cửa sổ tối đa"
            );
        }
        return resolved;
    }

    private LoanContractTerms terms(LoanApplication application, ScheduleCalculationSnapshot schedule) {
        return new LoanContractTerms(
                application.getRequestedAmount(), application.getRequestedTermMonths(),
                application.getAnnualInterestRateSnapshot(), application.getRepaymentMethodSnapshot(),
                schedule.getId(), schedule.getTotalInterest(), schedule.getTotalFees(),
                schedule.getTotalPenalties(), schedule.getTotalRepayment(), schedule.getFirstInstallment(),
                schedule.getMaximumInstallment(), schedule.getResponseHash(), schedule.getExpectedDisbursementDate()
        );
    }
}
