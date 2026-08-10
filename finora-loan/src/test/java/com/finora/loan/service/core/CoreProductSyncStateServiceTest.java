package com.finora.loan.service.core;

import com.finora.loan.config.FineractProperties;
import com.finora.loan.domain.core.FineractCommand;
import com.finora.loan.domain.core.FineractCommandStatus;
import com.finora.loan.domain.core.FineractCommandType;
import com.finora.loan.domain.core.FineractProductMapping;
import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.repository.core.FineractCommandRepository;
import com.finora.loan.repository.core.FineractProductMappingRepository;
import com.finora.loan.repository.product.LoanProductRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreProductSyncStateServiceTest {

    @Test
    void shouldNotStartSecondExecutionWhileProcessingLeaseIsActive() {
        Instant now = Instant.parse("2026-08-08T07:00:00Z");
        FineractCommand command = FineractCommand.pending(
                "command-1",
                FineractCommandType.CREATE_PRODUCT,
                1L,
                10L,
                "CREATE_PRODUCT:FINORA-LP-1-V1",
                "a".repeat(64),
                "{}",
                now.minusSeconds(30)
        );
        command.markProcessing(now.minusSeconds(30));

        FineractCommandRepository commandRepository = mock(FineractCommandRepository.class);
        FineractProperties properties = mock(FineractProperties.class);
        when(commandRepository.findByCommandIdForUpdate("command-1")).thenReturn(Optional.of(command));
        when(properties.processingLease()).thenReturn(Duration.ofMinutes(2));
        CoreProductSyncStateService stateService = new CoreProductSyncStateService(
                mock(LoanProductRepository.class),
                mock(FineractProductMappingRepository.class),
                commandRepository,
                properties,
                mock(HashingService.class),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        ProductSyncExecution execution = stateService.startExecution("command-1");

        assertThat(execution.executionRequired()).isFalse();
        verify(commandRepository, never()).saveAndFlush(command);
    }

    @Test
    void shouldReclaimProcessingCommandAfterLeaseExpires() {
        Instant now = Instant.parse("2026-08-08T07:00:00Z");
        FineractCommand command = FineractCommand.pending(
                "command-1",
                FineractCommandType.CREATE_PRODUCT,
                1L,
                10L,
                "CREATE_PRODUCT:FINORA-LP-1-V1",
                "a".repeat(64),
                "{}",
                now.minusSeconds(180)
        );
        command.markProcessing(now.minusSeconds(180));

        FineractCommandRepository commandRepository = mock(FineractCommandRepository.class);
        LoanProductRepository productRepository = mock(LoanProductRepository.class);
        FineractProductMappingRepository mappingRepository = mock(FineractProductMappingRepository.class);
        FineractProperties properties = mock(FineractProperties.class);
        LoanProduct product = mock(LoanProduct.class);
        FineractProductMapping mapping = mock(FineractProductMapping.class);
        when(commandRepository.findByCommandIdForUpdate("command-1")).thenReturn(Optional.of(command));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(mappingRepository.findById(10L)).thenReturn(Optional.of(mapping));
        when(mapping.getExternalId()).thenReturn("FINORA-LP-1-V1");
        when(properties.processingLease()).thenReturn(Duration.ofMinutes(2));
        CoreProductSyncStateService stateService = new CoreProductSyncStateService(
                productRepository,
                mappingRepository,
                commandRepository,
                properties,
                mock(HashingService.class),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        ProductSyncExecution execution = stateService.startExecution("command-1");

        assertThat(execution.executionRequired()).isTrue();
        assertThat(command.getStatus()).isEqualTo(FineractCommandStatus.PROCESSING);
        assertThat(command.getAttemptCount()).isEqualTo(2);
        verify(commandRepository).saveAndFlush(command);
    }
}
