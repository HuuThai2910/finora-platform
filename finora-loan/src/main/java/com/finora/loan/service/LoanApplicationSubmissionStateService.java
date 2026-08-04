package com.finora.loan.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.ActorType;
import com.finora.loan.domain.ApplicantFinancialSnapshot;
import com.finora.loan.domain.FineractMappingStatus;
import com.finora.loan.domain.FineractProductMapping;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.LoanApplicationStatus;
import com.finora.loan.domain.LoanApplicationStatusHistory;
import com.finora.loan.domain.LoanProduct;
import com.finora.loan.domain.ScheduleCalculationSnapshot;
import com.finora.loan.dto.request.CreateLoanApplicationRequest;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.ScheduleCalculationResult;
import com.finora.loan.repository.FineractProductMappingRepository;
import com.finora.loan.repository.LoanApplicationRepository;
import com.finora.loan.repository.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.LoanProductRepository;
import com.finora.loan.repository.ScheduleCalculationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoanApplicationSubmissionStateService {

    private final LoanApplicationRepository applicationRepository;
    private final LoanApplicationStatusHistoryRepository historyRepository;
    private final LoanProductRepository productRepository;
    private final FineractProductMappingRepository mappingRepository;
    private final ScheduleCalculationSnapshotRepository calculationRepository;
    private final ApplicationNumberGenerator numberGenerator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<LoanApplication> findExisting(String borrowerId, String idempotencyKey) {
        return applicationRepository.findByBorrowerIdAndIdempotencyKey(borrowerId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public ApplicationSubmissionContext prepareContext(CreateLoanApplicationRequest request) {
        // Context chỉ giữ scalar cần cho external call; không mang JPA entity ra ngoài transaction.
        LoanProduct product = productRepository.findById(request.loanProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", request.loanProductId()));
        product.requireAvailable();
        product.requireRequestedTerms(request.requestedAmount(), request.requestedTermMonths());
        FineractProductMapping mapping = mapping(product.getCurrentCoreMappingId());
        return new ApplicationSubmissionContext(
                product.getId(),
                product.getConfigurationVersion(),
                mapping.getId(),
                mapping.getFineractProductId(),
                product.getAnnualInterestRate(),
                product.getRepaymentMethod()
        );
    }

    /**
     * Tái kiểm tra Product dưới write lock rồi ghi Application, snapshot và history nguyên tử.
     */
    @Transactional
    public ApplicationPersistResult persist(
            String borrowerId,
            String idempotencyKey,
            String requestHash,
            String scheduleRequestId,
            String disclosureVersion,
            CreateLoanApplicationRequest request,
            ApplicationSubmissionContext expected,
            ScheduleCalculationResult scheduleResult
    ) {
        // Write lock ngăn update/deactivate/sync Product xen vào lúc đối chiếu snapshot vừa tính từ Fineract.
        LoanProduct product = productRepository.findByIdForSubmit(request.loanProductId())
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
        FineractProductMapping mapping = mapping(product.getCurrentCoreMappingId());
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
                numberGenerator.next(),
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
        applicationRepository.saveAndFlush(application);
        // Lưu schedule riêng vì payload nhiều kỳ; Application chỉ giữ ID tham chiếu snapshot đã sử dụng.
        ScheduleCalculationSnapshot calculation = ScheduleCalculationSnapshot.submission(
                application.getId(),
                scheduleRequestId,
                mapping.getFineractProductId(),
                scheduleResult,
                borrowerId,
                now
        );
        calculationRepository.saveAndFlush(calculation);
        application.attachSubmissionCalculationSnapshot(calculation.getId());
        applicationRepository.saveAndFlush(application);
        // History được ghi cùng transaction để không có trạng thái SUBMITTED mà thiếu dấu vết chuyển trạng thái.
        historyRepository.save(LoanApplicationStatusHistory.create(
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

    /** Detail endpoint tải đúng một snapshot; list endpoint cố ý không gọi hàm này để tránh N+1. */
    @Transactional(readOnly = true)
    public ScheduleCalculationSnapshot getCalculation(Long applicationId) {
        return calculationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Schedule Calculation Snapshot", "applicationId", applicationId));
    }

    private FineractProductMapping mapping(Long mappingId) {
        FineractProductMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", mappingId));
        if (mapping.getStatus() != FineractMappingStatus.SYNCED || mapping.getFineractProductId() == null) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_NOT_AVAILABLE", "Mapping Fineract chưa sẵn sàng");
        }
        return mapping;
    }
}
