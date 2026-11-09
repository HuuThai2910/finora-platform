package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.entity.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.core.entity.FineractProductMapping;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.FineractMappingStatus;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.service.application.ApplicationNumberGenerator;
import com.finora.loan.service.application.ApplicationPersistResult;
import com.finora.loan.service.application.ApplicationSubmissionContext;
import com.finora.loan.service.application.BorrowerExposureService;
import com.finora.loan.service.application.LoanApplicationSubmissionStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Các bước đọc/ghi database của luồng nộp hồ sơ vay, mỗi bước một transaction ngắn để lời gọi Fineract nằm ngoài
 * transaction.
 *
 * <p>Bước ghi khóa Product, đối chiếu lại snapshot đã chụp trước lời gọi mạng rồi ghi hồ sơ, snapshot lịch trả và
 * history trong cùng transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanApplicationSubmissionStateServiceImpl implements LoanApplicationSubmissionStateService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final LoanProductRepository loanProductRepository;
    private final FineractProductMappingRepository fineractProductMappingRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final ApplicationNumberGenerator applicationNumberGenerator;
    private final BorrowerExposureService borrowerExposureService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<LoanApplication> findExisting(String borrowerId, String idempotencyKey) {
        return loanApplicationRepository.findByBorrowerIdAndIdempotencyKey(borrowerId, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationSubmissionContext prepareContext(CreateLoanApplicationRequest request) {
        // Context chỉ giữ scalar cần cho external call; không mang JPA entity ra ngoài transaction.
        LoanProduct product = loanProductRepository.findById(request.loanProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", request.loanProductId()));
        product.requireAvailable();
        product.requireRequestedTerms(request.requestedAmount(), request.requestedTermMonths());
        FineractProductMapping mapping = requireSyncedMapping(product.getCurrentCoreMappingId());
        return new ApplicationSubmissionContext(
                product.getId(),
                product.getConfigurationVersion(),
                mapping.getId(),
                mapping.getFineractProductId(),
                mapping.getConfigVersion(),
                product.getAnnualInterestRate(),
                product.getRepaymentMethod()
        );
    }

    @Override
    @Transactional
    public ApplicationPersistResult persist(
            String borrowerId,
            String idempotencyKey,
            String requestHash,
            String scheduleRequestId,
            String disclosureVersion,
            CreateLoanApplicationRequest request,
            ApplicationSubmissionContext expected,
            ScheduleCalculationResult scheduleResult,
            BorrowerKycSnapshot kycSnapshot
    ) {
        // Write lock ngăn update/deactivate/sync Product xen vào lúc đối chiếu snapshot vừa tính từ Fineract.
        LoanProduct product = loanProductRepository.findByIdForSubmit(request.loanProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", request.loanProductId()));
        product.requireAvailable();
        product.requireRequestedTerms(request.requestedAmount(), request.requestedTermMonths());
        if (!product.getConfigurationVersion().equals(expected.productConfigurationVersion())
                || !product.getCurrentCoreMappingId().equals(expected.mappingId())) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_CHANGED",
                    "Sản phẩm đã thay đổi trong lúc tính lịch, vui lòng xem lại preview"
            );
        }
        FineractProductMapping mapping = requireSyncedMapping(product.getCurrentCoreMappingId());
        // Kiểm hạn mức dư nợ trong cùng transaction ghi hồ sơ, sau khóa advisory theo người vay (D5, LEGAL-LIMIT-01).
        borrowerExposureService.requireWithinLimit(borrowerId, request.requestedAmount());
        Instant now = Instant.now(clock);
        // Dữ liệu tự khai được đóng băng tại thời điểm submit để lần chấm điểm sau có thể tái hiện chính xác.
        ApplicantFinancialSnapshot financialSnapshot = ApplicantFinancialSnapshot.capture(
                request.declaredMonthlyIncome(),
                request.employmentLengthMonths(),
                request.educationLevel(),
                request.homeOwnership(),
                request.monthlyDebtObligations(),
                now
        );
        // Application giữ Product snapshot, nên thay đổi Product tương lai không sửa điều khoản hồ sơ lịch sử.
        LoanApplication application = LoanApplication.submit(
                applicationNumberGenerator.next(),
                borrowerId,
                idempotencyKey,
                requestHash,
                product,
                mapping,
                request.requestedAmount(),
                request.requestedTermMonths(),
                request.purposeCode(),
                request.purposeDetail(),
                financialSnapshot,
                request.expectedDisbursementDate(),
                disclosureVersion,
                now
        );
        // Mã hiển thị HS-yyyyMMdd-XXXX theo ngày nộp; khóa API/event vẫn là mã LA- ngẫu nhiên ở trên.
        application.assignApplicationCode(applicationNumberGenerator.nextCode(now));
        // eKYC chụp lúc nộp đi cùng hồ sơ trong một lần insert; worker chấm điểm đọc lại bản này (W2-KYC).
        application.attachKycSnapshot(kycSnapshot);
        loanApplicationRepository.saveAndFlush(application);
        // Lưu schedule riêng vì payload nhiều kỳ; Application chỉ giữ ID tham chiếu snapshot đã sử dụng.
        ScheduleCalculationSnapshot calculation = ScheduleCalculationSnapshot.submission(
                application.getId(),
                scheduleRequestId,
                mapping.getFineractProductId(),
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
                borrowerId,
                now
        );
        scheduleCalculationSnapshotRepository.saveAndFlush(calculation);
        application.attachSubmissionCalculationSnapshot(calculation.getId());
        loanApplicationRepository.saveAndFlush(application);
        // History được ghi cùng transaction để không có trạng thái SUBMITTED mà thiếu dấu vết chuyển trạng thái.
        loanApplicationStatusHistoryRepository.save(LoanApplicationStatusHistory.create(
                application.getId(),
                null,
                LoanApplicationStatus.SUBMITTED,
                "APPLICATION_SUBMITTED",
                null,
                ActorType.BORROWER,
                borrowerId,
                now
        ));
        return new ApplicationPersistResult(application, calculation);
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduleCalculationSnapshot getCalculation(Long applicationId) {
        return scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        applicationId, ScheduleCalculationPurpose.SUBMISSION_SCORING)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", applicationId));
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduleCalculationSnapshot getFinalCalculation(Long applicationId) {
        return scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        applicationId, ScheduleCalculationPurpose.CONTRACT)
                .orElse(null);
    }

    private FineractProductMapping requireSyncedMapping(Long mappingId) {
        FineractProductMapping mapping = fineractProductMappingRepository.findById(mappingId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", mappingId));
        if (mapping.getStatus() != FineractMappingStatus.SYNCED || mapping.getFineractProductId() == null) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_NOT_AVAILABLE", "Mapping Fineract chưa sẵn sàng");
        }
        return mapping;
    }
}
