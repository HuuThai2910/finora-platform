package com.finora.loan.service.impl;

import com.finora.loan.domain.FineractCommandStatus;
import com.finora.loan.domain.RepaymentMethod;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.FineractLoanProductGateway;
import com.finora.loan.integration.fineract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.FineractProductCreationResult;
import com.finora.loan.mapper.LoanProductMapper;
import com.finora.loan.service.CoreProductSyncStateService;
import com.finora.loan.service.ProductSyncExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoreProductSyncServiceImplTest {

    private static final String COMMAND_ID = "command-1";
    private static final String EXTERNAL_ID = "FINORA-LP-1-V1";

    @Mock CoreProductSyncStateService stateService;
    @Mock FineractLoanProductGateway gateway;
    @Mock LoanProductMapper productMapper;

    private CoreProductSyncServiceImpl service;
    private ProductSyncExecution execution;

    @BeforeEach
    void setUp() {
        service = new CoreProductSyncServiceImpl(stateService, gateway, productMapper);
        FineractProductConfiguration configuration = new FineractProductConfiguration(
                1L, 1L, "PERSONAL_STANDARD", "Vay tiêu dùng",
                new BigDecimal("10000000.00"), new BigDecimal("100000000.00"),
                6, 24, new BigDecimal("12.5000"), RepaymentMethod.ANNUITY,
                EXTERNAL_ID, "FINORA-FINERACT-V1"
        );
        execution = new ProductSyncExecution(
                COMMAND_ID, "CREATE_PRODUCT:" + EXTERNAL_ID,
                FineractCommandStatus.PROCESSING, configuration
        );
        when(stateService.startExecution(COMMAND_ID)).thenReturn(execution);
    }

    @Test
    void shouldReconcileExistingProductWithoutAnotherPost() {
        FineractProductCreationResult existing = new FineractProductCreationResult(101L, "{}");
        when(gateway.findProductByExternalId(EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.execute(COMMAND_ID);

        verify(gateway, never()).createProduct(execution.configuration(), execution.idempotencyKey());
        verify(stateService).complete(COMMAND_ID, existing);
    }

    @Test
    void shouldCompleteWhenPostTimesOutButReconciliationFindsCreatedProduct() {
        FineractProductCreationResult created = new FineractProductCreationResult(102L, "{}");
        when(gateway.findProductByExternalId(EXTERNAL_ID))
                .thenReturn(Optional.empty(), Optional.of(created));
        when(gateway.createProduct(execution.configuration(), execution.idempotencyKey()))
                .thenThrow(new FineractIntegrationException(
                        "FINERACT_UNAVAILABLE", "Không nhận được response", true, null));

        service.execute(COMMAND_ID);

        verify(stateService).complete(COMMAND_ID, created);
        verify(stateService, never()).fail(eq(COMMAND_ID), any(FineractIntegrationException.class));
    }
}
