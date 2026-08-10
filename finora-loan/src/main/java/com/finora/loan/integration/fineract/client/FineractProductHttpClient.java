package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.integration.fineract.mapper.FineractProductPayloadMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** REST adapter chỉ dành cho tạo và đối chiếu Loan Product trên Fineract. */
@Component
public class FineractProductHttpClient implements FineractLoanProductGateway {

    private final RestClient restClient;
    private final FineractProductPayloadMapper payloadMapper;
    private final FineractRequestExecutor requestExecutor;

    public FineractProductHttpClient(
            RestClient fineractRestClient,
            FineractProductPayloadMapper payloadMapper,
            FineractRequestExecutor requestExecutor
    ) {
        this.restClient = fineractRestClient;
        this.payloadMapper = payloadMapper;
        this.requestExecutor = requestExecutor;
    }

    @Override
    public Optional<FineractProductCreationResult> findProductByExternalId(String externalId) {
        JsonNode response = requestExecutor.execute("find-product-by-external-id", () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/loanproducts")
                        .queryParam("fields", "id,externalId")
                        .build())
                .headers(requestExecutor::authenticate)
                .retrieve()
                .body(JsonNode.class));
        return payloadMapper.findProductByExternalId(response, externalId);
    }

    @Override
    public FineractProductCreationResult createProduct(
            FineractProductConfiguration configuration,
            String idempotencyKey
    ) {
        JsonNode response = requestExecutor.execute("create-product", () -> restClient.post()
                .uri("/loanproducts")
                .headers(headers -> {
                    requestExecutor.authenticate(headers);
                    headers.set("Idempotency-Key", idempotencyKey);
                })
                .body(payloadMapper.toCreateProductPayload(configuration))
                .retrieve()
                .body(JsonNode.class));
        String snapshot = payloadMapper.sanitizedProductResponse(response);
        return new FineractProductCreationResult(response.path("resourceId").asLong(), snapshot);
    }
}
