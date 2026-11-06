package com.finora.loan.service.core.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.core.entity.FineractProductMapping;
import com.finora.loan.domain.core.enums.FineractMappingStatus;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.dto.core.request.RepaymentPreviewRequest;
import com.finora.loan.dto.core.response.RepaymentPreviewResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractScheduleGateway;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.mapper.core.RepaymentPreviewMapper;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.service.core.RepaymentPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Tính lịch trả dự kiến của một Product qua core lending (Fineract).
 *
 * <p>Chỉ Product đang bán và đã có mapping Fineract hợp lệ mới được tính; Fineract lỗi thì trả 503
 * {@code CORE_LENDING_UNAVAILABLE}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepaymentPreviewServiceImpl implements RepaymentPreviewService {

    private final LoanProductRepository loanProductRepository;
    private final FineractProductMappingRepository fineractProductMappingRepository;
    private final FineractScheduleGateway fineractScheduleGateway;
    private final RepaymentPreviewMapper repaymentPreviewMapper;
    private final Clock clock;

    @Override
    public RepaymentPreviewResponse preview(long productId, RepaymentPreviewRequest request) {
        // Chỉ Product ACTIVE với amount/term hợp lệ mới được gửi sang core để tránh preview điều khoản không bán.
        LoanProduct product = loanProductRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));
        product.requireAvailable();
        product.requireRequestedTerms(request.amount(), request.termMonths());
        FineractProductMapping mapping = fineractProductMappingRepository.findById(product.getCurrentCoreMappingId())
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
            ScheduleCalculationResult result = fineractScheduleGateway.calculateSchedule(new ScheduleCalculationRequest(
                    productId,
                    mapping.getFineractProductId(),
                    request.amount(),
                    request.termMonths(),
                    product.getAnnualInterestRate(),
                    product.getRepaymentMethod(),
                    mapping.getConfigVersion(),
                    LocalDate.now(clock),
                    request.expectedDisbursementDate()
            ));
            return repaymentPreviewMapper.toResponse(productId, result);
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
