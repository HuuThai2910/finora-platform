package com.finora.loan.service.core.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.core.FineractMappingStatus;
import com.finora.loan.domain.core.FineractProductMapping;
import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.dto.core.request.RepaymentPreviewRequest;
import com.finora.loan.dto.core.response.RepaymentPreviewResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractScheduleGateway;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.mapper.core.RepaymentPreviewMapper;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.service.core.RepaymentPreviewService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepaymentPreviewServiceImpl implements RepaymentPreviewService {

    private final LoanProductRepository productRepository;
    private final FineractProductMappingRepository mappingRepository;
    private final FineractScheduleGateway scheduleGateway;
    private final RepaymentPreviewMapper mapper;
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
            return mapper.toResponse(productId, result);
        } catch (FineractIntegrationException exception) {
            log.warn("Không thể tính lịch trả dự kiến: productId={}, code={}, retryable={}",
                    productId, exception.getCode(), exception.isRetryable());
            throw LoanBusinessException.serviceUnavailable(
                    "CORE_LENDING_UNAVAILABLE",
                    "Hệ thống tính lịch trả tạm thời không sẵn sàng"
            );
        }
    }

}
