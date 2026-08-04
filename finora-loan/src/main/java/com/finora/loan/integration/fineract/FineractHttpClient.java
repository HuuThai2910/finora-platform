package com.finora.loan.integration.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class FineractHttpClient implements FineractLoanProductGateway, FineractScheduleGateway {

    private final RestClient restClient;
    private final FineractProperties properties;
    private final FineractPayloadMapper payloadMapper;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private volatile Long previewClientId;

    public FineractHttpClient(
            RestClient fineractRestClient,
            FineractProperties properties,
            FineractPayloadMapper payloadMapper,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory
    ) {
        this.restClient = fineractRestClient;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    @Override
    public Optional<FineractProductCreationResult> findProductByExternalId(String externalId) {
        JsonNode response = execute("find-product-by-external-id", () -> restClient.get()
                // Fineract 1.15 chưa có endpoint lấy Loan Product trực tiếp bằng external ID.
                // Chỉ yêu cầu hai field cần thiết để giới hạn dữ liệu đi qua biên tích hợp.
                .uri(uriBuilder -> uriBuilder.path("/loanproducts")
                        .queryParam("fields", "id,externalId")
                        .build())
                .headers(headers -> headers.setBasicAuth(properties.username(), password()))
                .retrieve()
                .body(JsonNode.class));
        return payloadMapper.findProductByExternalId(response, externalId);
    }

    @Override
    public FineractProductCreationResult createProduct(
            FineractProductConfiguration configuration,
            String idempotencyKey
    ) {
        JsonNode response = execute("create-product", () -> restClient.post()
                .uri("/loanproducts")
                .headers(headers -> {
                    headers.setBasicAuth(properties.username(), password());
                    headers.set("Idempotency-Key", idempotencyKey);
                })
                .body(payloadMapper.toCreateProductPayload(configuration))
                .retrieve()
                .body(JsonNode.class));
        long resourceId = response == null ? 0L : response.path("resourceId").asLong(0L);
        if (resourceId <= 0) {
            throw new FineractIntegrationException(
                    "FINERACT_CONTRACT_MISMATCH",
                    "Fineract không trả resourceId hợp lệ khi tạo Product",
                    false,
                    null
            );
        }
        return new FineractProductCreationResult(resourceId, payloadMapper.sanitizedProductResponse(response));
    }

    @Override
    public ScheduleCalculationResult calculateSchedule(ScheduleCalculationRequest request) {
        long resolvedPreviewClientId = resolvePreviewClientId();
        JsonNode response = execute("calculate-schedule", () -> restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/loans").queryParam("command", "calculateLoanSchedule").build())
                .headers(headers -> headers.setBasicAuth(properties.username(), password()))
                .body(payloadMapper.toSchedulePayload(request, resolvedPreviewClientId))
                .retrieve()
                .body(JsonNode.class));
        if (response == null) {
            throw new FineractIntegrationException(
                    "FINERACT_CONTRACT_MISMATCH",
                    "Fineract trả response rỗng khi tính lịch",
                    false,
                    null
            );
        }
        return payloadMapper.toScheduleResult(request, resolvedPreviewClientId, response);
    }

    /**
     * Fineract 1.15 bắt buộc clientId cả khi chỉ calculate schedule. Client kỹ thuật được
     * resolve một lần theo external ID và chỉ dùng cho preview, không dùng để tạo khoản vay thật.
     */
    private long resolvePreviewClientId() {
        Long cached = previewClientId;
        if (cached != null) {
            return cached;
        }
        String externalId = properties.previewClientExternalId();
        JsonNode response = execute("find-preview-client", () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/clients")
                        .queryParam("externalId", externalId)
                        .queryParam("limit", 2)
                        .build())
                .headers(headers -> headers.setBasicAuth(properties.username(), password()))
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

    /** Circuit breaker ngăn request người dùng tiếp tục dồn vào Fineract khi dependency đang lỗi. */
    private <T> T execute(String operation, ExternalCall<T> call) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            return circuitBreakerFactory.create("fineract").run(
                    () -> call.execute(),
                    throwable -> {
                        throw mapException(throwable);
                    }
            );
        } finally {
            stopWatch.stop();
            log.info("Đã gọi dependency: dependency=Fineract, operation={}, latencyMs={}",
                    operation, stopWatch.getTotalTimeMillis());
        }
    }

    /**
     * Resilience4j có thể bọc lỗi gốc bằng CompletionException/ExecutionException.
     * Phải duyệt cause chain để timeout không bị phân loại nhầm thành lỗi vĩnh viễn.
     */
    FineractIntegrationException mapException(Throwable throwable) {
        Throwable cause = findKnownCause(throwable);
        if (cause instanceof FineractIntegrationException integrationException) {
            return integrationException;
        }
        if (cause instanceof RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            return new FineractIntegrationException(
                    retryable ? "FINERACT_TEMPORARY_ERROR" : "FINERACT_REQUEST_REJECTED",
                    "Fineract từ chối request với HTTP " + status.value(),
                    retryable,
                    responseException
            );
        }
        if (cause instanceof TimeoutException) {
            return new FineractIntegrationException(
                    "FINERACT_TIMEOUT",
                    "Apache Fineract không phản hồi trong thời gian cho phép",
                    true,
                    cause
            );
        }
        if (cause instanceof CallNotPermittedException) {
            return new FineractIntegrationException(
                    "FINERACT_CIRCUIT_OPEN",
                    "Tạm dừng gọi Apache Fineract vì dependency đang lỗi",
                    true,
                    cause
            );
        }
        if (cause instanceof ResourceAccessException) {
            return new FineractIntegrationException(
                    "FINERACT_UNAVAILABLE",
                    "Không thể kết nối Apache Fineract",
                    true,
                    cause
            );
        }
        log.error("Không ánh xạ được lỗi Fineract: exceptionType={}, rootCauseType={}",
                throwable.getClass().getName(), cause.getClass().getName());
        return new FineractIntegrationException(
                "FINERACT_UNEXPECTED_ERROR",
                "Lỗi không xác định khi gọi Apache Fineract",
                false,
                cause
        );
    }

    private Throwable findKnownCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable deepest = throwable;
        while (current != null) {
            deepest = current;
            if (current instanceof FineractIntegrationException
                    || current instanceof RestClientResponseException
                    || current instanceof TimeoutException
                    || current instanceof CallNotPermittedException
                    || current instanceof ResourceAccessException) {
                return current;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return deepest;
    }

    private String password() {
        if (properties.password() == null || properties.password().isBlank()) {
            throw new FineractIntegrationException(
                    "FINERACT_CREDENTIAL_MISSING",
                    "Chưa cấu hình FINERACT_API_PASSWORD",
                    false,
                    null
            );
        }
        return properties.password();
    }

    @FunctionalInterface
    private interface ExternalCall<T> {
        T execute();
    }
}
