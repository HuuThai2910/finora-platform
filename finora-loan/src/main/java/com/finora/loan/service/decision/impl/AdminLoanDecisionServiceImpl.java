package com.finora.loan.service.decision.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.mapper.decision.AdminLoanDecisionMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.decision.AdminDecisionResult;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import com.finora.loan.service.decision.AdminLoanDecisionStateService;
import com.finora.loan.support.HashingService;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLoanDecisionServiceImpl implements AdminLoanDecisionService {

    private static final int HISTORY_LIMIT = 20;

    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final BorrowerEligibilityCheckRepository eligibilityRepository;
    private final BorrowerCreditProfileRepository creditProfileRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final AdminLoanDecisionStateService stateService;
    private final AdminLoanDecisionMapper mapper;
    private final LoanApplicationMapper applicationMapper;
    private final HashingService hashingService;
    private final MockCurrentUserProvider currentUser;

    /**
     * Không truyền trạng thái nghĩa là xem tất cả hồ sơ, nhưng vẫn chỉ đọc một page có giới hạn.
     * Assessment được tải theo batch để số query không tăng theo số hồ sơ trên trang.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            int page,
            int size
    ) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        Page<LoanApplication> applications = status == null
                ? applicationRepository.findAll(pageable)
                : applicationRepository.findByStatus(status, pageable);
        Map<Long, CreditScoringAssessment> assessments = assessmentRepository
                .findAllById(applications.getContent().stream()
                        .map(LoanApplication::getLatestCreditAssessmentId)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .stream()
                .collect(Collectors.toMap(CreditScoringAssessment::getId, Function.identity()));
        return PageResponse.from(applications.map(application -> mapper.toSummary(
                application, assessments.get(application.getLatestCreditAssessmentId()))));
    }

    /** Detail dùng số query cố định và chỉ đọc snapshot local; không gọi User, AI hoặc Fineract. */
    @Override
    @Transactional(readOnly = true)
    public AdminLoanReviewDetailResponse reviewDetail(String applicationNumber) {
        LoanApplication application = application(applicationNumber);
        ScheduleCalculationSnapshot schedule = scheduleRepository.findByApplicationId(application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
        BorrowerEligibilityCheck eligibility = eligibilityRepository
                .findFirstByApplicationIdOrderByCreatedAtDescIdDesc(application.getId())
                .orElse(null);
        BorrowerCreditProfile creditProfile = creditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElse(null);
        CreditScoringAssessment assessment = application.getLatestCreditAssessmentId() == null
                ? null
                : assessmentRepository.findByIdAndApplicationId(
                        application.getLatestCreditAssessmentId(), application.getId()).orElse(null);
        var history = historyRepository.findByLoanApplicationId(
                        application.getId(),
                        PageRequest.of(0, HISTORY_LIMIT,
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(applicationMapper::toHistoryResponse)
                .getContent();
        return mapper.toDetail(application, schedule, eligibility, creditProfile, assessment, history);
    }

    @Override
    public AdminLoanDecisionResponse approve(
            String applicationNumber,
            String idempotencyKey,
            ApproveLoanApplicationRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new DecisionFingerprint("APPROVE", request));
        AdminDecisionResult result = executeWithDuplicateRecovery(
                applicationNumber, normalizedKey, requestHash,
                () -> stateService.approve(
                        applicationNumber, normalizedKey, requestHash, request, currentUser.adminUserId()));
        log.info("Admin đã duyệt hồ sơ: applicationNumber={}, contractNumber={}, actorId={}",
                applicationNumber, result.contract().getContractNumber(), currentUser.adminUserId());
        return mapper.toDecision(result.application(), result.contract());
    }

    @Override
    public AdminLoanDecisionResponse reject(
            String applicationNumber,
            String idempotencyKey,
            RejectLoanApplicationRequest request
    ) {
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new DecisionFingerprint("REJECT", request));
        AdminDecisionResult result = executeWithDuplicateRecovery(
                applicationNumber, normalizedKey, requestHash,
                () -> stateService.reject(
                        applicationNumber, normalizedKey, requestHash, request, currentUser.adminUserId()));
        log.info("Admin đã từ chối hồ sơ: applicationNumber={}, reasonCode={}, actorId={}",
                applicationNumber, request.reasonCode(), currentUser.adminUserId());
        return mapper.toDecision(result.application(), null);
    }

    private AdminDecisionResult executeWithDuplicateRecovery(
            String applicationNumber,
            String idempotencyKey,
            String requestHash,
            java.util.function.Supplier<AdminDecisionResult> command
    ) {
        try {
            return command.get();
        } catch (DataIntegrityViolationException conflict) {
            AdminDecisionResult committed = stateService.findCommittedByKey(
                    applicationNumber, idempotencyKey, requestHash);
            if (committed != null
                    && committed.application().getApplicationNumber().equals(applicationNumber)) {
                return committed;
            }
            throw conflict;
        }
    }

    private LoanApplication application(String applicationNumber) {
        return applicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private record DecisionFingerprint(String action, Object request) {
    }
}
