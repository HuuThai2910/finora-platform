package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.integration.fineract.mapper.FineractProductPayloadMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/** REST adapter chỉ dành cho tạo và đối chiếu Loan Product trên Fineract. */
@Component
public class FineractProductHttpClient implements FineractLoanProductGateway {

    private final RestClient restClient;
    private final FineractProductPayloadMapper fineractProductPayloadMapper;
    private final FineractRequestExecutor fineractRequestExecutor;

    public FineractProductHttpClient(
            RestClient fineractRestClient,
            FineractProductPayloadMapper fineractProductPayloadMapper,
            FineractRequestExecutor fineractRequestExecutor
    ) {
        this.restClient = fineractRestClient;
        this.fineractProductPayloadMapper = fineractProductPayloadMapper;
        this.fineractRequestExecutor = fineractRequestExecutor;
    }

    @Override
    public Optional<FineractProductCreationResult> findProductByExternalId(
            FineractProductConfiguration configuration
    ) {
        String externalId = configuration.externalId();
        JsonNode response = fineractRequestExecutor.execute(FineractCallGroup.PRODUCT,
                "find-product-by-external-id", () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/loanproducts")
                        .queryParam("fields", "id,externalId")
                        .build())
                .headers(fineractRequestExecutor::authenticate)
                .retrieve()
                .body(JsonNode.class));
        Optional<FineractProductCreationResult> reconciled =
                fineractProductPayloadMapper.findProductByExternalId(response, externalId);
        if (reconciled.isEmpty()) {
            return Optional.empty();
        }
        long productId = reconciled.orElseThrow().resourceId();
        JsonNode product = fineractRequestExecutor.execute(FineractCallGroup.PRODUCT,
                "read-reconciled-product", () -> restClient.get()
                .uri("/loanproducts/{id}", productId)
                .headers(fineractRequestExecutor::authenticate)
                .retrieve()
                .body(JsonNode.class));
        fineractProductPayloadMapper.validateReconciledProduct(
                product, configuration.configVersion(), configuration.repaymentMethod());
        return reconciled;
    }

    @Override
    public FineractProductCreationResult createProduct(
            FineractProductConfiguration configuration,
            String idempotencyKey
    ) {
        JsonNode response = fineractRequestExecutor.execute(
                FineractCallGroup.PRODUCT,
                "create-product",
                () -> restClient.post()
                .uri("/loanproducts")
                .headers(headers -> {
                    fineractRequestExecutor.authenticate(headers);
                    headers.set("Idempotency-Key", idempotencyKey);
                })
                .body(fineractProductPayloadMapper.toCreateProductPayload(configuration))
                .retrieve()
                .body(JsonNode.class)
        );
        String snapshot = fineractProductPayloadMapper.sanitizedProductResponse(response);
        return new FineractProductCreationResult(response.path("resourceId").asLong(), snapshot);
    }
}
