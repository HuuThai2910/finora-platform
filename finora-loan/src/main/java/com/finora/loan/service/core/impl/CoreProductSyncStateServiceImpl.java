package com.finora.loan.service.core.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.FineractProperties;
import com.finora.loan.domain.core.entity.FineractCommand;
import com.finora.loan.domain.core.entity.FineractProductMapping;
import com.finora.loan.domain.core.enums.FineractCommandStatus;
import com.finora.loan.domain.core.enums.FineractCommandType;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.repository.core.FineractCommandRepository;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.service.core.CoreProductSyncStateService;
import com.finora.loan.service.core.ProductSyncExecution;
import com.finora.loan.service.core.ProductSyncPreparation;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Các bước transaction của đồng bộ Product sang Fineract: chuẩn bị command, nhận lượt thực thi và ghi kết quả; HTTP
 * Fineract chạy ngoài các transaction này.
 *
 * <p>Command bền vững theo business key Product + configuration version; lease {@code processing-lease} chống hai lượt
 * cùng gọi Fineract cho một command.</p>
 */
@Service
@RequiredArgsConstructor
public class CoreProductSyncStateServiceImpl implements CoreProductSyncStateService {

    private static final String SYSTEM_ACTOR = "LOAN_CORE_SYNC_WORKER";

    private final LoanProductRepository loanProductRepository;
    private final FineractProductMappingRepository fineractProductMappingRepository;
    private final FineractCommandRepository fineractCommandRepository;
    private final FineractProperties fineractProperties;
    private final HashingService hashingService;
    private final Clock clock;

    /**
     * External ID Product trên Fineract. Không gian tên rỗng giữ dạng cũ {@code FINORA-LP-<id>-V<n>}; có không gian tên
     * (DB Loan khác dùng chung Fineract) thì thành {@code FINORA-<ns>-LP-<id>-V<n>} để id Product không đụng nhau.
     */
    static String productExternalId(String namespace, long productId, long configurationVersion) {
        String prefix = namespace == null || namespace.isBlank() ? "FINORA-LP-" : "FINORA-" + namespace + "-LP-";
        return prefix + productId + "-V" + configurationVersion;
    }

    @Override
    @Transactional
    public ProductSyncPreparation prepare(long productId, long expectedVersion, String actorId) {
        LoanProduct product = loanProductRepository.findByIdForSubmit(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));

        long configurationVersion = product.getConfigurationVersion();
        String externalId = productExternalId(fineractProperties.productNamespace(), productId, configurationVersion);
        String idempotencyKey = "CREATE_PRODUCT:" + externalId;
        FineractProductConfiguration configuration = configuration(product, externalId);
        String requestHash = hashingService.sha256(configuration);

        // Business key gồm Product + configuration version; cùng cấu hình luôn quay về cùng logical command.
        FineractCommand existingCommand = fineractCommandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existingCommand != null) {
            if (!existingCommand.getRequestHash().equals(requestHash)) {
                throw new IllegalStateException("Business key đồng bộ Product bị dùng lại với payload khác");
            }
            boolean reopened = existingCommand.getStatus() == FineractCommandStatus.FAILED;
            if (reopened) {
                Instant now = Instant.now(clock);
                FineractProductMapping mapping = fineractProductMappingRepository
                        .findById(existingCommand.getMappingId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Fineract Product Mapping", "id", existingCommand.getMappingId()));
                product.markCoreSyncPending(expectedVersion, actorId, now);
                mapping.markRetryPending(null, null, actorId, now);
                existingCommand.reopen(now);
                loanProductRepository.saveAndFlush(product);
                fineractProductMappingRepository.saveAndFlush(mapping);
                fineractCommandRepository.saveAndFlush(existingCommand);
            }
            // PENDING/PROCESSING có thể đang được request khác xử lý; không tranh chấp cùng command.
            boolean executionRequired = reopened
                    || existingCommand.getStatus() == FineractCommandStatus.RETRY_PENDING;
            return new ProductSyncPreparation(existingCommand.getCommandId(), configuration, executionRequired);
        }

        Instant now = Instant.now(clock);
        // Mapping PENDING và Product PENDING được ghi trước external call để tiến trình có thể phục hồi sau restart.
        FineractProductMapping mapping = FineractProductMapping.pending(
                productId,
                configurationVersion,
                externalId,
                fineractProperties.productConfigVersion(),
                requestHash,
                actorId,
                now
        );
        fineractProductMappingRepository.saveAndFlush(mapping);
        product.markCoreSyncPending(expectedVersion, actorId, now);
        loanProductRepository.saveAndFlush(product);

        String commandId = UUID.randomUUID().toString();
        FineractCommand command = FineractCommand.pending(
                commandId,
                FineractCommandType.CREATE_PRODUCT,
                productId,
                mapping.getId(),
                idempotencyKey,
                requestHash,
                hashingService.toJson(configuration),
                now
        );
        fineractCommandRepository.save(command);
        return new ProductSyncPreparation(commandId, configuration, true);
    }

    @Override
    @Transactional
    public ProductSyncExecution startExecution(String commandId) {
        // Pessimistic lock chỉ giữ trong transaction local này, không kéo dài sang cuộc gọi HTTP.
        FineractCommand command = fineractCommandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        if (command.getStatus() == FineractCommandStatus.SUCCEEDED) {
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        Instant now = Instant.now(clock);
        if (command.getStatus() == FineractCommandStatus.PROCESSING
                && command.getUpdatedAt().isAfter(now.minus(fineractProperties.processingLease()))) {
            // Request khác vẫn còn lease; không được tạo cuộc gọi Fineract thứ hai cho cùng command.
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        if (command.getStatus() == FineractCommandStatus.RETRY_PENDING
                && command.getNextRetryAt() != null
                && command.getNextRetryAt().isAfter(now)) {
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        command.markProcessing(now);
        fineractCommandRepository.saveAndFlush(command);
        LoanProduct product = loanProductRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        FineractProductMapping mapping = fineractProductMappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        return new ProductSyncExecution(
                commandId,
                command.getIdempotencyKey(),
                command.getStatus(),
                configuration(product, mapping.getExternalId()),
                true
        );
    }

    @Override
    @Transactional
    public void complete(String commandId, FineractProductCreationResult result) {
        FineractCommand command = fineractCommandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        if (command.getStatus() == FineractCommandStatus.SUCCEEDED) {
            return;
        }
        FineractProductMapping mapping = fineractProductMappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        LoanProduct product = loanProductRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        Instant now = Instant.now(clock);
        mapping.markSynced(result.resourceId(), SYSTEM_ACTOR, now);
        fineractProductMappingRepository.saveAndFlush(mapping);
        product.markCoreSynced(mapping.getId(), SYSTEM_ACTOR, now);
        loanProductRepository.saveAndFlush(product);
        command.markSucceeded(result.responseSnapshotJson(), now);
        fineractCommandRepository.save(command);
    }

    @Override
    @Transactional
    public void fail(String commandId, FineractIntegrationException failure) {
        FineractCommand command = fineractCommandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        FineractProductMapping mapping = fineractProductMappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        LoanProduct product = loanProductRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        Instant now = Instant.now(clock);
        if (failure.isRetryable() && command.getAttemptCount() < fineractProperties.maxAttempts()) {
            // Backoff tăng theo số lần thử để dependency có thời gian phục hồi và tránh retry dồn dập.
            Instant retryAt = now.plus(fineractProperties.retryBackoff().multipliedBy(command.getAttemptCount()));
            command.markRetryPending(failure.getCode(), failure.getMessage(), retryAt, now);
            mapping.markRetryPending(failure.getCode(), failure.getMessage(), SYSTEM_ACTOR, now);
        } else {
            command.markFailed(failure.getCode(), failure.getMessage(), now);
            mapping.markFailed(failure.getCode(), failure.getMessage(), SYSTEM_ACTOR, now);
            product.markCoreSyncFailed(SYSTEM_ACTOR, now);
            loanProductRepository.saveAndFlush(product);
        }
        fineractProductMappingRepository.saveAndFlush(mapping);
        fineractCommandRepository.save(command);
    }

    @Override
    @Transactional(readOnly = true)
    public FineractCommand getCommand(String commandId) {
        return fineractCommandRepository.findByCommandId(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
    }

    @Override
    @Transactional(readOnly = true)
    public LoanProduct getProduct(long productId) {
        return loanProductRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findDueCommandIds(int limit) {
        Instant now = Instant.now(clock);
        return fineractCommandRepository.findDueCommandIds(
                FineractCommandStatus.PENDING,
                FineractCommandStatus.RETRY_PENDING,
                FineractCommandStatus.PROCESSING,
                now,
                now.minus(fineractProperties.processingLease()),
                PageRequest.of(0, limit)
        );
    }

    private FineractProductConfiguration configuration(LoanProduct product, String externalId) {
        return new FineractProductConfiguration(
                product.getId(),
                product.getConfigurationVersion(),
                product.getCode(),
                product.getName(),
                product.getMinAmount(),
                product.getMaxAmount(),
                product.getMinTermMonths(),
                product.getMaxTermMonths(),
                product.getAnnualInterestRate(),
                product.getRepaymentMethod(),
                externalId,
                fineractProperties.productConfigVersion()
        );
    }
}
