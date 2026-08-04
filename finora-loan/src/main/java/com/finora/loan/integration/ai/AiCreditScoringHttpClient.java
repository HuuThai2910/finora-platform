package com.finora.loan.integration.ai;

import com.finora.loan.config.AiCreditProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

@Component
@Slf4j
public class AiCreditScoringHttpClient implements AiCreditScoringGateway {

    private final RestClient restClient;
    private final AiCreditProperties properties;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public AiCreditScoringHttpClient(
            @Qualifier("aiCreditRestClient") RestClient restClient,
            AiCreditProperties properties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /** Gọi đúng một hồ sơ; requestId chỉ dùng correlation, không log payload chứa thông tin tài chính. */
    @Override
    public AiCreditScoreResponse score(AiCreditScoreRequest request, String requestId) {
        StopWatch watch = new StopWatch();
        watch.start();
        try {
            AiCreditScoreResponse response = circuitBreakerFactory.create("ai-credit").run(
                    () -> restClient.post()
                            .uri("/api/v1/ai/credit/score")
                            .header("X-Request-Id", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(AiCreditScoreResponse.class),
                    throwable -> {
                        throw mapException(throwable);
                    }
            );
            validate(response);
            return response;
        } finally {
            watch.stop();
            log.info("Đã gọi dependency: dependency=AI_CREDIT, operation=score, requestId={}, latencyMs={}",
                    requestId, watch.getTotalTimeMillis());
        }
    }

    private void validate(AiCreditScoreResponse response) {
        if (response == null
                || response.pdProbability() == null
                || response.riskScore() == null
                || response.evaluationScore() == null
                || response.creditGrade() == null
                || response.suggestedLimit() == null
                || response.decision() == null
                || response.modelVersion() == null) {
            throw contractMismatch("AI trả response thiếu field bắt buộc");
        }
        if (response.pdProbability().compareTo(BigDecimal.ZERO) < 0
                || response.pdProbability().compareTo(BigDecimal.ONE) > 0
                || response.riskScore() < 0
                || response.riskScore() > 100
                || !java.util.Set.of("A", "B", "C", "D").contains(response.creditGrade())) {
            throw contractMismatch("AI trả score hoặc credit grade ngoài miền hợp lệ");
        }
        if (!properties.modelVersion().equals(response.modelVersion())) {
            throw new AiCreditIntegrationException(
                    "AI_MODEL_VERSION_MISMATCH",
                    "AI trả model version không khớp version Loan yêu cầu",
                    false,
                    null
            );
        }
    }

    private AiCreditIntegrationException mapException(Throwable throwable) {
        Throwable cause = knownCause(throwable);
        if (cause instanceof AiCreditIntegrationException integrationException) {
            return integrationException;
        }
        if (cause instanceof RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            return new AiCreditIntegrationException(
                    retryable ? "AI_TEMPORARY_ERROR" : "AI_REQUEST_REJECTED",
                    "AI Service từ chối request với HTTP " + status.value(),
                    retryable,
                    responseException
            );
        }
        if (cause instanceof CallNotPermittedException) {
            return new AiCreditIntegrationException(
                    "AI_CIRCUIT_OPEN", "Tạm dừng gọi AI vì dependency đang lỗi", true, cause);
        }
        if (cause instanceof ResourceAccessException) {
            return new AiCreditIntegrationException(
                    "AI_UNAVAILABLE", "Không thể kết nối AI Service", true, cause);
        }
        log.error("Không ánh xạ được lỗi AI Credit: exceptionType={}, rootCauseType={}",
                throwable.getClass().getName(), cause.getClass().getName());
        return new AiCreditIntegrationException(
                "AI_UNEXPECTED_ERROR", "Lỗi không xác định khi gọi AI Service", false, cause);
    }

    private Throwable knownCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable deepest = throwable;
        while (current != null) {
            deepest = current;
            if (current instanceof AiCreditIntegrationException
                    || current instanceof RestClientResponseException
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

    private AiCreditIntegrationException contractMismatch(String message) {
        return new AiCreditIntegrationException("AI_CONTRACT_MISMATCH", message, false, null);
    }
}
