package com.finora.loan.service.impl;

import com.finora.loan.domain.FineractCommand;
import com.finora.loan.domain.FineractCommandStatus;
import com.finora.loan.domain.LoanProduct;
import com.finora.loan.dto.response.CoreProductSyncResponse;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.FineractLoanProductGateway;
import com.finora.loan.integration.fineract.FineractProductCreationResult;
import com.finora.loan.mapper.LoanProductMapper;
import com.finora.loan.service.CoreProductSyncService;
import com.finora.loan.service.CoreProductSyncStateService;
import com.finora.loan.service.ProductSyncExecution;
import com.finora.loan.service.ProductSyncPreparation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Điều phối đồng bộ Product mà không giữ transaction database trong lúc gọi Fineract. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoreProductSyncServiceImpl implements CoreProductSyncService {

    private final CoreProductSyncStateService stateService;
    private final FineractLoanProductGateway gateway;
    private final LoanProductMapper productMapper;

    /**
     * Trả lại command cũ khi admin gửi trùng cùng business key. Command mới hoặc
     * retry đến lượt sẽ được thực thi sau khi transaction chuẩn bị đã đóng.
     */
    @Override
    public CoreProductSyncResponse synchronize(long productId, long version) {
        // Commit command/mapping bền vững trước; tuyệt đối không giữ transaction khi gọi Fineract.
        ProductSyncPreparation preparation = stateService.prepare(productId, version);
        if (preparation.executionRequired()) {
            execute(preparation.commandId());
        }
        // Response đọc lại state đã commit, không suy đoán kết quả từ biến tạm của external call.
        return response(productId, preparation.commandId());
    }

    /** Worker và HTTP endpoint dùng chung một execution path idempotent. */
    public void execute(String commandId) {
        // Khóa command ngắn hạn để request và retry worker không cùng thực thi một command.
        ProductSyncExecution execution = stateService.startExecution(commandId);
        if (execution.status() == FineractCommandStatus.SUCCEEDED) {
            return;
        }
        try {
            FineractProductCreationResult result = createOrReconcile(execution);
            stateService.complete(commandId, result);
            log.info("Đồng bộ Fineract Product thành công: commandId={}, productId={}, fineractProductId={}",
                    commandId, execution.configuration().loanProductId(), result.resourceId());
        } catch (FineractIntegrationException failure) {
            stateService.fail(commandId, failure);
            log.warn("Đồng bộ Fineract Product chưa thành công: commandId={}, code={}, retryable={}",
                    commandId, failure.getCode(), failure.isRetryable());
        }
    }

    /**
     * POST có thể đã tạo Product ở Fineract nhưng Loan bị timeout trước khi nhận response.
     * Vì vậy mọi lần thực thi đều đối chiếu external ID trước và sau lỗi để retry không tạo bản logic thứ hai.
     */
    private FineractProductCreationResult createOrReconcile(ProductSyncExecution execution) {
        String externalId = execution.configuration().externalId();
        Optional<FineractProductCreationResult> existing = gateway.findProductByExternalId(externalId);
        if (existing.isPresent()) {
            log.info("Đã đối chiếu Product tồn tại trên Fineract: commandId={}, productId={}, fineractProductId={}",
                    execution.commandId(), execution.configuration().loanProductId(), existing.get().resourceId());
            return existing.get();
        }

        try {
            return gateway.createProduct(execution.configuration(), execution.idempotencyKey());
        } catch (FineractIntegrationException createFailure) {
            try {
                Optional<FineractProductCreationResult> created = gateway.findProductByExternalId(externalId);
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
        LoanProduct product = stateService.getProduct(productId);
        FineractCommand command = stateService.getCommand(commandId);
        return new CoreProductSyncResponse(
                productMapper.toResponse(product),
                commandId,
                command.getStatus(),
                command.getLastErrorCode()
        );
    }
}
