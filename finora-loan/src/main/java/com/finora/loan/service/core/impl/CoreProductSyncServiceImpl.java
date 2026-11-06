package com.finora.loan.service.core.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.domain.core.entity.FineractCommand;
import com.finora.loan.domain.core.enums.FineractCommandStatus;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.dto.core.response.CoreProductSyncResponse;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractLoanProductGateway;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.mapper.product.LoanProductMapper;
import com.finora.loan.service.core.CoreProductSyncService;
import com.finora.loan.service.core.CoreProductSyncStateService;
import com.finora.loan.service.core.ProductSyncExecution;
import com.finora.loan.service.core.ProductSyncPreparation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Điều phối đồng bộ Product mà không giữ transaction database trong lúc gọi Fineract. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoreProductSyncServiceImpl implements CoreProductSyncService {

    private final CoreProductSyncStateService coreProductSyncStateService;
    private final FineractLoanProductGateway fineractLoanProductGateway;
    private final LoanProductMapper loanProductMapper;

    @Override
    public CoreProductSyncResponse synchronize(long productId, long version) {
        SecurityUtils.requirePermission(OpsFunction.LOAN_PRODUCT.action());
        // Commit command/mapping bền vững trước; tuyệt đối không giữ transaction khi gọi Fineract.
        ProductSyncPreparation preparation = coreProductSyncStateService.prepare(
                productId, version, SecurityUtils.getCurrentUserId());
        if (preparation.executionRequired()) {
            execute(preparation.commandId());
        }
        // Response đọc lại state đã commit, không suy đoán kết quả từ biến tạm của external call.
        return response(productId, preparation.commandId());
    }

    @Override
    public void execute(String commandId) {
        // Khóa command ngắn hạn để request và retry worker không cùng thực thi một command.
        ProductSyncExecution execution = coreProductSyncStateService.startExecution(commandId);
        if (!execution.executionRequired()) {
            return;
        }
        try {
            FineractProductCreationResult result = createOrReconcile(execution);
            coreProductSyncStateService.complete(commandId, result);
            log.info("Đồng bộ Fineract Product thành công: commandId={}, productId={}, fineractProductId={}",
                    commandId, execution.configuration().loanProductId(), result.resourceId());
        } catch (FineractIntegrationException failure) {
            coreProductSyncStateService.fail(commandId, failure);
            log.warn("Đồng bộ Fineract Product chưa thành công: commandId={}, code={}, retryable={}, detail={}",
                    commandId, failure.getCode(), failure.isRetryable(), failure.getMessage());
        }
    }

    /**
     * POST có thể đã tạo Product ở Fineract nhưng Loan bị timeout trước khi nhận response.
     * Vì vậy mọi lần thực thi đều đối chiếu external ID trước và sau lỗi để retry không tạo bản logic thứ hai.
     */
    private FineractProductCreationResult createOrReconcile(ProductSyncExecution execution) {
        String externalId = execution.configuration().externalId();
        Optional<FineractProductCreationResult> existing = fineractLoanProductGateway.findProductByExternalId(
                execution.configuration());
        if (existing.isPresent()) {
            log.info("Đã đối chiếu Product tồn tại trên Fineract: commandId={}, productId={}, fineractProductId={}",
                    execution.commandId(), execution.configuration().loanProductId(), existing.get().resourceId());
            return existing.get();
        }

        try {
            return fineractLoanProductGateway.createProduct(execution.configuration(), execution.idempotencyKey());
        } catch (FineractIntegrationException createFailure) {
            try {
                Optional<FineractProductCreationResult> created = fineractLoanProductGateway.findProductByExternalId(
                        execution.configuration());
                if (created.isPresent()) {
                    log.warn("POST Product không có kết quả chắc chắn nhưng đối chiếu đã tìm thấy Product: "
                                    + "commandId={}, productId={}, fineractProductId={}, originalCode={}",
                            execution.commandId(), execution.configuration().loanProductId(),
                            created.get().resourceId(), createFailure.getCode());
                    return created.get();
                }
            } catch (FineractIntegrationException reconciliationFailure) {
                // Giữ lỗi POST làm nguyên nhân chính; lỗi đối chiếu được gắn kèm để phục vụ điều tra kỹ thuật.
                createFailure.addSuppressed(reconciliationFailure);
            }
            throw createFailure;
        }
    }

    private CoreProductSyncResponse response(long productId, String commandId) {
        LoanProduct product = coreProductSyncStateService.getProduct(productId);
        FineractCommand command = coreProductSyncStateService.getCommand(commandId);
        return new CoreProductSyncResponse(
                loanProductMapper.toResponse(product),
                commandId,
                command.getStatus(),
                command.getLastErrorCode()
        );
    }
}
