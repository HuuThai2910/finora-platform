package com.finora.loan.service.decision.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
import com.finora.loan.repository.application.LoanApplicationAdminSpecifications;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.scoring.entity.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.model.ApplicationServicingSummary;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminAssessmentExplanationResponse;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.mapper.decision.AdminLoanDecisionMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.application.LoanApplicationTimelineService;
import com.finora.loan.service.decision.AdminApplicationServicingService;
import com.finora.loan.service.decision.AdminDecisionResult;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import com.finora.loan.service.decision.AdminLoanDecisionStateService;
import com.finora.loan.util.HashingService;
import com.finora.loan.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Use case dành cho admin đọc evidence và quyết định hồ sơ; AI chỉ cung cấp đầu vào.
 *
 * <p>Các bước ghi đi qua {@link AdminLoanDecisionStateService}; request trùng {@code Idempotency-Key} chạm unique key
 * thì đọc lại quyết định đã commit.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLoanDecisionServiceImpl implements AdminLoanDecisionService {

    private static final int HISTORY_LIMIT = 20;

    /** Múi giờ nghiệp vụ (D7) để đổi ngày lọc "ngày nộp" sang mốc thời gian. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final LoanApplicationRepository loanApplicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final BorrowerEligibilityCheckRepository borrowerEligibilityCheckRepository;
    private final BorrowerCreditProfileRepository borrowerCreditProfileRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final LoanContractRepository loanContractRepository;
    private final AdminLoanDecisionStateService adminLoanDecisionStateService;
    private final AdminLoanDecisionMapper adminLoanDecisionMapper;
    private final LoanApplicationMapper loanApplicationMapper;
    private final HashingService hashingService;
    private final StoredAiCreditResponseReader storedAiCreditResponseReader;
    private final LoanApplicationTimelineService loanApplicationTimelineService;
    private final AdminApplicationServicingService adminApplicationServicingService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            int page,
            int size
    ) {
        return listApplications(status, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            String borrowerId,
            int page,
            int size
    ) {
        return listApplications(status, borrowerId, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            String borrowerId,
            String q,
            int page,
            int size
    ) {
        return listApplications(status, borrowerId, q, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            String borrowerId,
            String q,
            ApplicationServicingFlag servicing,
            int page,
            int size
    ) {
        return listApplications(status, borrowerId, q, servicing, AdminApplicationFilter.NONE, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminLoanReviewSummaryResponse> listApplications(
            LoanApplicationStatus status,
            String borrowerId,
            String q,
            ApplicationServicingFlag servicing,
            AdminApplicationFilter filter,
            int page,
            int size
    ) {
        AdminApplicationFilter advanced = filter == null ? AdminApplicationFilter.NONE : filter.validate();
        String borrower = borrowerId == null || borrowerId.isBlank() ? null : borrowerId.trim();
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        Page<LoanApplication> applications;
        String code = SearchPatterns.containsCode(q);
        if (!advanced.isEmpty()) {
            // Bảng lọc nâng cao: một Specification ghép mọi tiêu chí (kể cả ô tìm và cờ vận hành) để phân trang và tổng
            // số đúng ở DB. Không lọc nâng cao thì giữ các truy vấn cũ bên dưới.
            applications = loanApplicationRepository.findAll(LoanApplicationAdminSpecifications.matching(
                    status, borrower, code, SearchPatterns.trimmed(q), servicing, advanced, BUSINESS_ZONE), pageable);
        } else if (servicing != null) {
            // Không tìm thì "%" khớp mọi hồ sơ (mã tra cứu luôn có) và ID người vay rỗng không khớp ai.
            String trimmed = SearchPatterns.trimmed(q);
            applications = loanApplicationRepository.searchAdminByServicing(
                    status, borrower, code == null ? "%" : code, trimmed == null ? "" : trimmed,
                    servicing == ApplicationServicingFlag.OVERDUE,
                    servicing == ApplicationServicingFlag.RESCHEDULE_PENDING,
                    servicing == ApplicationServicingFlag.INCIDENT,
                    pageable);
        } else if (code != null) {
            applications = loanApplicationRepository.searchAdmin(
                    status, borrower, code, SearchPatterns.trimmed(q), pageable);
        } else if (borrower != null) {
            applications = status == null
                    ? loanApplicationRepository.findByBorrowerId(borrower, pageable)
                    : loanApplicationRepository.findByBorrowerIdAndStatus(borrower, status, pageable);
        } else {
            applications = status == null
                    ? loanApplicationRepository.findAll(pageable)
                    : loanApplicationRepository.findByStatus(status, pageable);
        }
        Map<Long, CreditScoringAssessment> assessments = creditScoringAssessmentRepository
                .findAllById(applications.getContent().stream()
                        .map(LoanApplication::getLatestCreditAssessmentId)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .stream()
                .collect(Collectors.toMap(CreditScoringAssessment::getId, Function.identity()));
        Map<Long, ApplicationServicingSummary> servicingSummaries = adminApplicationServicingService.summariesOf(
                applications.getContent().stream().map(LoanApplication::getId).toList());
        return PageResponse.from(applications.map(application -> adminLoanDecisionMapper.toSummary(
                application, assessments.get(application.getLatestCreditAssessmentId()),
                servicingSummaries.get(application.getId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLoanReviewDetailResponse reviewDetail(String applicationNumber) {
        LoanApplication application = application(applicationNumber);
        ScheduleCalculationSnapshot initialSchedule = scheduleCalculationSnapshotRepository
                .findByApplicationIdAndPurpose(application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", application.getId()));
        ScheduleCalculationSnapshot finalSchedule = scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.CONTRACT)
                .orElse(null);
        BorrowerEligibilityCheck eligibility = borrowerEligibilityCheckRepository
                .findFirstByApplicationIdOrderByCreatedAtDescIdDesc(application.getId())
                .orElse(null);
        BorrowerCreditProfile creditProfile = borrowerCreditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElse(null);
        CreditScoringAssessment assessment = application.getLatestCreditAssessmentId() == null
                ? null
                : creditScoringAssessmentRepository.findByIdAndApplicationId(
                        application.getLatestCreditAssessmentId(), application.getId()).orElse(null);
        var history = loanApplicationStatusHistoryRepository.findByLoanApplicationId(
                        application.getId(),
                        PageRequest.of(0, HISTORY_LIMIT,
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(loanApplicationMapper::toHistoryResponse)
                .getContent();
        LoanContractRepository.ContractCodeView contract = loanContractRepository
                .findContractReferenceByApplicationId(application.getId())
                .orElse(null);
        // Lý do chuyển thẩm định chỉ nằm trong snapshot response AI đã lưu; snapshot cũ không có thì để rỗng.
        StoredAiCreditResponse snapshot = assessment == null ? null : storedAiCreditResponseReader.read(assessment);
        List<String> reviewReasons = snapshot == null || snapshot.reviewReasons() == null
                ? List.of()
                : snapshot.reviewReasons();
        return adminLoanDecisionMapper.toDetail(
                application, initialSchedule, finalSchedule, eligibility, creditProfile, assessment, history,
                contract == null ? null : contract.getContractNumber(), reviewReasons,
                loanApplicationTimelineService.timeline(application),
                contract == null ? null : contract.getContractCode(),
                adminApplicationServicingService.summaryOf(application.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminAssessmentExplanationResponse assessmentExplanation(String applicationNumber) {
        LoanApplication application = application(applicationNumber);
        if (application.getLatestCreditAssessmentId() == null) {
            throw new ResourceNotFoundException(
                    "Credit Scoring Assessment", "applicationNumber", applicationNumber);
        }

        CreditScoringAssessment assessment = creditScoringAssessmentRepository
                .findByIdAndApplicationId(application.getLatestCreditAssessmentId(), application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "applicationNumber", applicationNumber));

        // Lần chấm hỏng hoặc đang chờ thì chưa có gì để giải thích. Phân biệt với hồ sơ
        // chưa từng chấm ở trên để admin biết nên đợi hay nên yêu cầu chấm lại.
        StoredAiCreditResponse snapshot = storedAiCreditResponseReader.read(assessment);
        if (snapshot == null) {
            throw LoanBusinessException.badRequest(
                    "ASSESSMENT_EXPLANATION_UNAVAILABLE",
                    "Lần chấm điểm này chưa có kết quả để giải thích");
        }

        return new AdminAssessmentExplanationResponse(
                assessment.getId(),
                assessment.getActualModelVersion(),
                assessment.getDecisionPolicyVersion(),
                assessment.getScoredAt(),
                snapshot.borrowerExplanation(),
                snapshot.modelExplanation(),
                snapshot.ruleTrace());
    }

    @Override
    public AdminLoanDecisionResponse approve(
            String applicationNumber,
            String idempotencyKey,
            ApproveLoanApplicationRequest request
    ) {
        SecurityUtils.requirePermission(OpsFunction.LOAN_APPLICATION.action());
        String adminId = SecurityUtils.getCurrentUserId();
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new DecisionFingerprint("APPROVE", request));
        AdminDecisionResult result = executeWithDuplicateRecovery(
                applicationNumber, normalizedKey, requestHash,
                () -> adminLoanDecisionStateService.approve(
                        applicationNumber, normalizedKey, requestHash, request, adminId));
        log.info("Admin đã duyệt hồ sơ: applicationNumber={}, contractNumber={}, termsConfirmationStatus={}, actorId={}",
                applicationNumber,
                result.contract() == null ? null : result.contract().getContractNumber(),
                result.application().getTermsConfirmationStatus(),
                adminId);
        return adminLoanDecisionMapper.toDecision(result.application(), result.contract());
    }

    @Override
    public AdminLoanDecisionResponse reject(
            String applicationNumber,
            String idempotencyKey,
            RejectLoanApplicationRequest request
    ) {
        SecurityUtils.requirePermission(OpsFunction.LOAN_APPLICATION.action());
        String adminId = SecurityUtils.getCurrentUserId();
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hashingService.sha256(new DecisionFingerprint("REJECT", request));
        AdminDecisionResult result = executeWithDuplicateRecovery(
                applicationNumber, normalizedKey, requestHash,
                () -> adminLoanDecisionStateService.reject(
                        applicationNumber, normalizedKey, requestHash, request, adminId));
        log.info("Admin đã từ chối hồ sơ: applicationNumber={}, reasonCode={}, actorId={}",
                applicationNumber, request.reasonCode(), adminId);
        return adminLoanDecisionMapper.toDecision(result.application(), null);
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
            AdminDecisionResult committed = adminLoanDecisionStateService.findCommittedByKey(
                    applicationNumber, idempotencyKey, requestHash);
            if (committed != null
                    && committed.application().getApplicationNumber().equals(applicationNumber)) {
                return committed;
            }
            throw conflict;
        }
    }

    private LoanApplication application(String applicationNumber) {
        return loanApplicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }

    private record DecisionFingerprint(String action, Object request) {
    }
}
