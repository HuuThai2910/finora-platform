package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractProperties;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.fineract.mapper.FineractSchedulePayloadMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** REST adapter chỉ dành cho Client preview kỹ thuật và tính lịch trả dự kiến. */
@Component
public class FineractScheduleHttpClient implements FineractScheduleGateway {

    private final RestClient restClient;
    private final FineractProperties properties;
    private final FineractSchedulePayloadMapper payloadMapper;
    private final FineractRequestExecutor requestExecutor;
    private volatile Long previewClientId;

    public FineractScheduleHttpClient(
            RestClient fineractRestClient,
            FineractProperties properties,
            FineractSchedulePayloadMapper payloadMapper,
            FineractRequestExecutor requestExecutor
    ) {
        this.restClient = fineractRestClient;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
        this.requestExecutor = requestExecutor;
    }

    @Override
    public ScheduleCalculationResult calculateSchedule(ScheduleCalculationRequest request) {
        long resolvedPreviewClientId = resolvePreviewClientId();
        JsonNode response = requestExecutor.execute("calculate-schedule", () -> restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/loans")
                        .queryParam("command", "calculateLoanSchedule")
                        .build())
                .headers(requestExecutor::authenticate)
                .body(payloadMapper.toSchedulePayload(request, resolvedPreviewClientId))
                .retrieve()
                .body(JsonNode.class));
        return payloadMapper.toScheduleResult(request, resolvedPreviewClientId, response);
    }

    /** Resolve một lần Client kỹ thuật; Client này không được dùng để tạo khoản vay thật. */
    private long resolvePreviewClientId() {
        Long cached = previewClientId;
        if (cached != null) {
            return cached;
        }
        String externalId = properties.previewClientExternalId();
        JsonNode response = requestExecutor.execute("find-preview-client", () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/clients")
                        .queryParam("externalId", externalId)
                        .queryParam("limit", 2)
                        .build())
                .headers(requestExecutor::authenticate)
                .retrieve()
                .body(JsonNode.class));
        long resolved = payloadMapper.findClientIdByExternalId(response, externalId)
                .orElseThrow(() -> new FineractIntegrationException(
                        "FINERACT_PREVIEW_CLIENT_MISSING",
                        "Chưa có Client kỹ thuật để tính lịch trả dự kiến",
                        false,
                        null
                ));
        previewClientId = resolved;
        return resolved;
    }
}
