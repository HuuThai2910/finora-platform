package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.FineractProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Dùng chung xác thực, circuit breaker, đo latency và phân loại lỗi cho mọi Fineract adapter. */
@Component
@Slf4j
public class FineractRequestExecutor {

    private static final int MAX_VALIDATION_ERRORS = 3;
    private static final int MAX_DETAIL_PART_LENGTH = 300;
    private static final int MAX_ERROR_DETAIL_LENGTH = 1_000;

    private final FineractProperties properties;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final ObjectMapper objectMapper;

    public FineractRequestExecutor(
            FineractProperties properties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.objectMapper = objectMapper;
    }

    public void authenticate(HttpHeaders headers) {
        if (properties.password() == null || properties.password().isBlank()) {
            throw new FineractIntegrationException(
                    "FINERACT_CREDENTIAL_MISSING",
                    "Chưa cấu hình FINERACT_API_PASSWORD",
                    false,
                    null
            );
        }
        headers.setBasicAuth(properties.username(), properties.password());
    }

    /**
     * Mỗi adapter khai báo nhóm chức năng và tên thao tác. Nhóm quyết định circuit breaker;
     * operation chỉ dùng cho log/metric và tuyệt đối không chứa ID hay dữ liệu người dùng.
     */
    public <T> T execute(FineractCallGroup group, String operation, ExternalCall<T> call) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            return circuitBreakerFactory.create(group.circuitBreakerName()).run(
                    call::execute,
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

    /** Duyệt cause chain để timeout bị wrapper bọc vẫn được đánh dấu retryable. */
    FineractIntegrationException mapException(Throwable throwable) {
        FineractIntegrationException integrationException = findCause(
                throwable, FineractIntegrationException.class);
        if (integrationException != null) {
            return integrationException;
        }
        RestClientResponseException responseException = findCause(
                throwable, RestClientResponseException.class);
        if (responseException != null) {
            HttpStatusCode status = responseException.getStatusCode();
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            return new FineractIntegrationException(
                    retryable ? "FINERACT_TEMPORARY_ERROR" : "FINERACT_REQUEST_REJECTED",
                    rejectedRequestMessage(responseException),
                    retryable,
                    responseException
            );
        }
        Throwable timeoutCause = findTimeoutCause(throwable);
        if (timeoutCause != null) {
            return new FineractIntegrationException(
                    "FINERACT_TIMEOUT", "Apache Fineract không phản hồi trong thời gian cho phép",
                    true, timeoutCause);
        }
        CallNotPermittedException callNotPermitted = findCause(throwable, CallNotPermittedException.class);
        if (callNotPermitted != null) {
            return new FineractIntegrationException(
                    "FINERACT_CIRCUIT_OPEN", "Tạm dừng gọi Apache Fineract vì dependency đang lỗi",
                    true, callNotPermitted);
        }
        ResourceAccessException resourceAccess = findCause(throwable, ResourceAccessException.class);
        if (resourceAccess != null) {
            return new FineractIntegrationException(
                    "FINERACT_UNAVAILABLE", "Không thể kết nối Apache Fineract", true, resourceAccess);
        }
        Throwable cause = deepestCause(throwable);
        log.error("Không ánh xạ được lỗi Fineract: exceptionType={}, rootCauseType={}",
                throwable.getClass().getName(), cause.getClass().getName());
        return new FineractIntegrationException(
                "FINERACT_UNEXPECTED_ERROR", "Lỗi không xác định khi gọi Apache Fineract", false, cause);
    }

    private Throwable findTimeoutCause(Throwable throwable) {
        TimeoutException timeLimiterTimeout = findCause(throwable, TimeoutException.class);
        return timeLimiterTimeout != null
                ? timeLimiterTimeout
                : findCause(throwable, SocketTimeoutException.class);
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable deepest = throwable;
        while (current != null) {
            deepest = current;
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return deepest;
    }

    /**
     * Chỉ lấy các trường validation đã cho phép thay vì lưu raw response có thể chứa dữ liệu nhạy cảm
     * hoặc HTML từ proxy. Giới hạn số lỗi và độ dài để log/command không bị phình không kiểm soát.
     */
    private String rejectedRequestMessage(RestClientResponseException exception) {
        String prefix = "Fineract từ chối request với HTTP " + exception.getStatusCode().value();
        String detail = extractValidationDetail(
                exception.getResponseBodyAsString(StandardCharsets.UTF_8)
        );
        return detail == null ? prefix : prefix + ": " + detail;
    }

    String extractValidationDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<String> details = new ArrayList<>();
            JsonNode errors = root.path("errors");
            if (errors.isArray()) {
                for (JsonNode error : errors) {
                    if (details.size() >= MAX_VALIDATION_ERRORS) {
                        break;
                    }
                    String parameter = safeText(error.path("parameterName"));
                    String message = firstSafeText(
                            error.path("defaultUserMessage"),
                            error.path("developerMessage")
                    );
                    if (parameter != null && message != null) {
                        details.add(parameter + " - " + message);
                    } else if (message != null) {
                        details.add(message);
                    } else if (parameter != null) {
                        details.add(parameter);
                    }
                }
            }
            if (details.isEmpty()) {
                String rootMessage = firstSafeText(
                        root.path("defaultUserMessage"),
                        root.path("developerMessage")
                );
                if (rootMessage != null) {
                    details.add(rootMessage);
                }
            }
            if (details.isEmpty()) {
                return null;
            }
            return truncate(String.join("; ", details), MAX_ERROR_DETAIL_LENGTH);
        } catch (JsonProcessingException ignored) {
            // Không đưa raw body không đúng contract vào log hoặc durable command.
            return null;
        }
    }

    private String firstSafeText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            String value = safeText(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String safeText(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            return null;
        }
        String singleLine = node.textValue().replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return truncate(singleLine, MAX_DETAIL_PART_LENGTH);
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength) + "...";
    }

    @FunctionalInterface
    public interface ExternalCall<T> {
        T execute();
    }
}
