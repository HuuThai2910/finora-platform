package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractProperties;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.fineract.mapper.FineractSchedulePayloadMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** REST adapter chỉ dành cho Client preview kỹ thuật và tính lịch trả dự kiến. */
@Component
public class FineractScheduleHttpClient implements FineractScheduleGateway {

    private final RestClient restClient;
    private final FineractProperties fineractProperties;
    private final FineractSchedulePayloadMapper fineractSchedulePayloadMapper;
    private final FineractRequestExecutor fineractRequestExecutor;
    private volatile Long previewClientId;

    public FineractScheduleHttpClient(
            RestClient fineractRestClient,
            FineractProperties fineractProperties,
            FineractSchedulePayloadMapper fineractSchedulePayloadMapper,
            FineractRequestExecutor fineractRequestExecutor
    ) {
        this.restClient = fineractRestClient;
        this.fineractProperties = fineractProperties;
        this.fineractSchedulePayloadMapper = fineractSchedulePayloadMapper;
        this.fineractRequestExecutor = fineractRequestExecutor;
    }

    @Override
    public ScheduleCalculationResult calculateSchedule(ScheduleCalculationRequest request) {
        long resolvedPreviewClientId = resolvePreviewClientId();
        JsonNode response = fineractRequestExecutor.execute(
                FineractCallGroup.SCHEDULE,
                "calculate-schedule",
                () -> restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/loans")
                        .queryParam("command", "calculateLoanSchedule")
                        .build())
                .headers(fineractRequestExecutor::authenticate)
                .body(fineractSchedulePayloadMapper.toSchedulePayload(request, resolvedPreviewClientId))
                .retrieve()
                .body(JsonNode.class)
        );
        return fineractSchedulePayloadMapper.toScheduleResult(request, resolvedPreviewClientId, response);
    }

    /** Resolve một lần Client kỹ thuật; Client này không được dùng để tạo khoản vay thật. */
    private long resolvePreviewClientId() {
        Long cached = previewClientId;
        if (cached != null) {
            return cached;
        }
        String externalId = fineractProperties.previewClientExternalId();
        JsonNode response = fineractRequestExecutor.execute(
                FineractCallGroup.SCHEDULE,
                "find-preview-client",
                () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/clients")
                        .queryParam("externalId", externalId)
                        .queryParam("limit", 2)
                        .build())
                .headers(fineractRequestExecutor::authenticate)
                .retrieve()
                .body(JsonNode.class)
        );
        long resolved = fineractSchedulePayloadMapper.findClientIdByExternalId(response, externalId)
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
