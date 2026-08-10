package com.finora.loan.service.core;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.FineractProperties;
import com.finora.loan.domain.core.FineractCommand;
import com.finora.loan.domain.core.FineractCommandStatus;
import com.finora.loan.domain.core.FineractCommandType;
import com.finora.loan.domain.core.FineractProductMapping;
import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.repository.core.FineractCommandRepository;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CoreProductSyncStateService {

    private static final String SYSTEM_ACTOR = "LOAN_CORE_SYNC_WORKER";

    private final LoanProductRepository productRepository;
    private final FineractProductMappingRepository mappingRepository;
    private final FineractCommandRepository commandRepository;
    private final FineractProperties properties;
    private final HashingService hashingService;
    private final Clock clock;

    /** Ghi mapping và command trước; HTTP Fineract chỉ được chạy sau khi transaction này commit. */
    @Transactional
    public ProductSyncPreparation prepare(long productId, long expectedVersion, String actorId) {
        LoanProduct product = productRepository.findByIdForSubmit(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));

        long configurationVersion = product.getConfigurationVersion();
        String externalId = "FINORA-LP-" + productId + "-V" + configurationVersion;
        String idempotencyKey = "CREATE_PRODUCT:" + externalId;
        FineractProductConfiguration configuration = configuration(product, externalId);
        String requestHash = hashingService.sha256(configuration);

        // Business key gồm Product + configuration version; cùng cấu hình luôn quay về cùng logical command.
        FineractCommand existingCommand = commandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existingCommand != null) {
            if (!existingCommand.getRequestHash().equals(requestHash)) {
                throw new IllegalStateException("Business key đồng bộ Product bị dùng lại với payload khác");
            }
            boolean reopened = existingCommand.getStatus() == FineractCommandStatus.FAILED;
            if (reopened) {
                Instant now = Instant.now(clock);
                FineractProductMapping mapping = mappingRepository.findById(existingCommand.getMappingId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Fineract Product Mapping", "id", existingCommand.getMappingId()));
                product.markCoreSyncPending(expectedVersion, actorId, now);
                mapping.markRetryPending(null, null, actorId, now);
                existingCommand.reopen(now);
                productRepository.saveAndFlush(product);
                mappingRepository.saveAndFlush(mapping);
                commandRepository.saveAndFlush(existingCommand);
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
                properties.productConfigVersion(),
                requestHash,
                actorId,
                now
        );
        mappingRepository.saveAndFlush(mapping);
        product.markCoreSyncPending(expectedVersion, actorId, now);
        productRepository.saveAndFlush(product);

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
        commandRepository.save(command);
        return new ProductSyncPreparation(commandId, configuration, true);
    }

    @Transactional
    /** Cấp attempt dưới DB lock rồi đóng transaction trước khi service gọi HTTP Fineract. */
    public ProductSyncExecution startExecution(String commandId) {
        // Pessimistic lock chỉ giữ trong transaction local này, không kéo dài sang cuộc gọi HTTP.
        FineractCommand command = commandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        if (command.getStatus() == FineractCommandStatus.SUCCEEDED) {
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        Instant now = Instant.now(clock);
        if (command.getStatus() == FineractCommandStatus.PROCESSING
                && command.getUpdatedAt().isAfter(now.minus(properties.processingLease()))) {
            // Request khác vẫn còn lease; không được tạo cuộc gọi Fineract thứ hai cho cùng command.
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        if (command.getStatus() == FineractCommandStatus.RETRY_PENDING
                && command.getNextRetryAt() != null
                && command.getNextRetryAt().isAfter(now)) {
            return ProductSyncExecution.skip(commandId, command.getIdempotencyKey(), command.getStatus());
        }
        command.markProcessing(now);
        commandRepository.saveAndFlush(command);
        LoanProduct product = productRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        FineractProductMapping mapping = mappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        return new ProductSyncExecution(
                commandId,
                command.getIdempotencyKey(),
                command.getStatus(),
                configuration(product, mapping.getExternalId()),
                true
        );
    }

    /** Ghi resource ID Fineract, mapping và Product SYNCED trong cùng transaction. */
    @Transactional
    public void complete(String commandId, FineractProductCreationResult result) {
        FineractCommand command = commandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        if (command.getStatus() == FineractCommandStatus.SUCCEEDED) {
            return;
        }
        FineractProductMapping mapping = mappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        LoanProduct product = productRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        Instant now = Instant.now(clock);
        mapping.markSynced(result.resourceId(), SYSTEM_ACTOR, now);
        mappingRepository.saveAndFlush(mapping);
        product.markCoreSynced(mapping.getId(), SYSTEM_ACTOR, now);
        productRepository.saveAndFlush(product);
        command.markSucceeded(result.responseSnapshotJson(), now);
        commandRepository.save(command);
    }

    /** Lỗi tạm thời được xếp lịch retry; lỗi vĩnh viễn mở lại quyền sửa/sync Product. */
    @Transactional
    public void fail(String commandId, FineractIntegrationException failure) {
        FineractCommand command = commandRepository.findByCommandIdForUpdate(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
        FineractProductMapping mapping = mappingRepository.findById(command.getMappingId())
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Product Mapping", "id", command.getMappingId()));
        LoanProduct product = productRepository.findById(command.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", command.getAggregateId()));
        Instant now = Instant.now(clock);
        if (failure.isRetryable() && command.getAttemptCount() < properties.maxAttempts()) {
            // Backoff tăng theo số lần thử để dependency có thời gian phục hồi và tránh retry dồn dập.
            Instant retryAt = now.plus(properties.retryBackoff().multipliedBy(command.getAttemptCount()));
            command.markRetryPending(failure.getCode(), failure.getMessage(), retryAt, now);
            mapping.markRetryPending(failure.getCode(), failure.getMessage(), SYSTEM_ACTOR, now);
        } else {
            command.markFailed(failure.getCode(), failure.getMessage(), now);
            mapping.markFailed(failure.getCode(), failure.getMessage(), SYSTEM_ACTOR, now);
            product.markCoreSyncFailed(SYSTEM_ACTOR, now);
            productRepository.saveAndFlush(product);
        }
        mappingRepository.saveAndFlush(mapping);
        commandRepository.save(command);
    }

    @Transactional(readOnly = true)
    public FineractCommand getCommand(String commandId) {
        return commandRepository.findByCommandId(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Fineract Command", "commandId", commandId));
    }

    @Transactional(readOnly = true)
    public LoanProduct getProduct(long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Product", "id", productId));
    }

    /** Worker chỉ lấy ID của một batch đến hạn, không giữ entity/transaction khi gọi HTTP. */
    @Transactional(readOnly = true)
    public List<String> findDueCommandIds(int limit) {
        Instant now = Instant.now(clock);
        return commandRepository.findDueCommandIds(
                FineractCommandStatus.PENDING,
                FineractCommandStatus.RETRY_PENDING,
                FineractCommandStatus.PROCESSING,
                now,
                now.minus(properties.processingLease()),
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
                properties.productConfigVersion()
        );
    }
}
