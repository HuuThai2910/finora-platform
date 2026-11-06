package com.finora.loan.service.application;

import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;

import java.util.Optional;

/**
 * Các bước đọc/ghi database của luồng nộp hồ sơ vay, mỗi bước một transaction ngắn để lời gọi Fineract nằm ngoài
 * transaction.
 */
public interface LoanApplicationSubmissionStateService {

    /** Request lặp được trả từ DB trước khi gọi lại Fineract, tránh tạo lịch và hồ sơ lần hai. */
    Optional<LoanApplication> findExisting(String borrowerId, String idempotencyKey);

    /** Chụp scalar Product/mapping rồi đóng transaction; bước persist sẽ lock và đối chiếu lại. */
    ApplicationSubmissionContext prepareContext(CreateLoanApplicationRequest request);

    /**
     * Tái kiểm tra Product dưới write lock rồi ghi Application, snapshot và history nguyên tử.
     */
    ApplicationPersistResult persist(
            String borrowerId,
            String idempotencyKey,
            String requestHash,
            String scheduleRequestId,
            String disclosureVersion,
            CreateLoanApplicationRequest request,
            ApplicationSubmissionContext expected,
            ScheduleCalculationResult scheduleResult,
            BorrowerKycSnapshot kycSnapshot
    );

    /** Detail endpoint tải đúng một snapshot; list endpoint cố ý không gọi hàm này để tránh N+1. */
    ScheduleCalculationSnapshot getCalculation(Long applicationId);

    /** Final schedule chỉ xuất hiện sau scoring; Optional giúp hồ sơ đang xử lý vẫn đọc chi tiết bình thường. */
    ScheduleCalculationSnapshot getFinalCalculation(Long applicationId);
}
