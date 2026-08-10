package com.finora.loan.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.FineractMappingStatus;
import com.finora.loan.domain.FineractProductMapping;
import com.finora.loan.domain.LoanProduct;
import com.finora.loan.dto.request.RepaymentPreviewRequest;
import com.finora.loan.dto.response.RepaymentPreviewResponse;
import com.finora.loan.dto.response.SchedulePeriodResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.FineractScheduleGateway;
import com.finora.loan.integration.fineract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.ScheduleCalculationResult;
import com.finora.loan.repository.FineractProductMappingRepository;
import com.finora.loan.repository.LoanProductRepository;
import com.finora.loan.service.RepaymentPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepaymentPreviewServiceImpl implements RepaymentPreviewService {

    private final LoanProductRepository productRepository;
    private final FineractProductMappingRepository mappingRepository;
    private final FineractScheduleGateway scheduleGateway;
    private final Clock clock;

    /**
     * Đọc scalar Product/mapping trước rồi đóng transaction repository; HTTP Fineract không giữ DB connection.
     */
    @Override
    public RepaymentPreviewResponse preview(long productId, RepaymentPreviewRequest request) {
        // Chỉ Product ACTIVE với amount/term hợp lệ mới được gửi sang core để tránh preview điều khoản không bán.
        LoanProduct product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));
        product.requireAvailable();
        product.requireRequestedTerms(request.amount(), request.termMonths());
        FineractProductMapping mapping = mappingRepository.findById(product.getCurrentCoreMappingId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fineract Product Mapping", "id", product.getCurrentCoreMappingId()));
        if (mapping.getStatus() != FineractMappingStatus.SYNCED || mapping.getFineractProductId() == null) {
            throw LoanBusinessException.conflict(
                    "LOAN_PRODUCT_NOT_AVAILABLE",
                    "Sản phẩm chưa có mapping Fineract hợp lệ"
            );
        }
        try {
            // submittedOnDate là hôm nay; expectedDisbursementDate là ngày tương lai borrower chọn, không được nhập nhầm nhau.
            ScheduleCalculationResult result = scheduleGateway.calculateSchedule(new ScheduleCalculationRequest(
                    productId,
                    mapping.getFineractProductId(),
                    request.amount(),
                    request.termMonths(),
                    product.getAnnualInterestRate(),
                    product.getRepaymentMethod(),
                    LocalDate.now(clock),
                    request.expectedDisbursementDate()
            ));
            return toResponse(productId, result);
        } catch (FineractIntegrationException exception) {
            log.warn("Không thể tính lịch trả dự kiến: productId={}, code={}, retryable={}",
                    productId, exception.getCode(), exception.isRetryable());
            throw LoanBusinessException.serviceUnavailable(
                    "CORE_LENDING_UNAVAILABLE",
                    "Hệ thống tính lịch trả tạm thời không sẵn sàng"
            );
        }
    }

    private RepaymentPreviewResponse toResponse(long productId, ScheduleCalculationResult result) {
        return new RepaymentPreviewResponse(
                productId,
                result.amount(),
                result.termMonths(),
                result.annualInterestRate(),
                "PERCENT_PER_YEAR",
                result.repaymentMethod(),
                result.estimatedDisbursementDate(),
                result.firstInstallment(),
                result.maximumInstallment(),
                result.totalPrincipal(),
                result.totalInterest(),
                result.totalFees(),
                result.totalPenalties(),
                result.totalRepayment(),
                result.periods().stream()
                        .map(period -> new SchedulePeriodResponse(
                                period.period(),
                                period.fromDate(),
                                period.dueDate(),
                                period.daysInPeriod(),
                                period.principal(),
                                period.interest(),
                                period.fees(),
                                period.penalties(),
                                period.totalDue(),
                                period.outstandingBalance()
                        ))
                        .toList(),
                result.calculationPolicyVersion()
        );
    }
}
