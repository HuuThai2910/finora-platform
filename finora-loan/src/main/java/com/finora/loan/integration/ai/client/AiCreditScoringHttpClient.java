package com.finora.loan.integration.ai.client;

import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.math.BigDecimal;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Slf4j
public class AiCreditScoringHttpClient implements AiCreditScoringGateway {

    /** finora-ai chỉ nhận POST /credit/explain từ Loan (mã này) hoặc từ admin (JWT). */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

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
                            .uri("/api/v1/ai/credit/explain")
                            .header("X-Request-Id", requestId)
                            .headers(this::addInternalToken)
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

    /** Không cấu hình mã thì không gửi header rỗng: AI trả 401 rõ ràng thay vì so chuỗi rỗng. */
    private void addInternalToken(HttpHeaders headers) {
        if (!properties.internalToken().isEmpty()) {
            headers.set(INTERNAL_TOKEN_HEADER, properties.internalToken());
        }
    }

    private void validate(AiCreditScoreResponse response) {
        if (response == null
                || response.pdProbability() == null
                || response.riskScore() == null
                || response.evaluationScore() == null
                || response.creditGrade() == null
                || response.decision() == null
                || response.modelVersion() == null
                || response.decisionPolicyVersion() == null
                || response.borrowerExplanation() == null
                || response.modelExplanation() == null
                || response.ruleTrace() == null) {
            throw contractMismatch("AI trả response thiếu field bắt buộc");
        }
        if (response.pdProbability().compareTo(BigDecimal.ZERO) < 0
                || response.pdProbability().compareTo(BigDecimal.ONE) > 0
                || response.riskScore() < 0
                || response.riskScore() > 100
                || !response.creditGrade().matches("^[A-Z][A-Z0-9+\\-]{0,7}$")) {
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

    AiCreditIntegrationException mapException(Throwable throwable) {
        Throwable cause = knownCause(throwable);
        if (cause instanceof AiCreditIntegrationException integrationException) {
            return integrationException;
        }
        if (cause instanceof RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                // Sai cấu hình chứ không phải lỗi tạm: thử lại vẫn bị từ chối. Mã riêng để log
                // chỉ thẳng vào AI_INTERNAL_TOKEN thay vì một lỗi 4xx chung chung.
                return new AiCreditIntegrationException(
                        "AI_INTERNAL_AUTH_REJECTED",
                        "AI Service từ chối mã nội bộ của Loan (HTTP " + status.value()
                                + "); kiểm AI_INTERNAL_TOKEN ở finora-loan và finora-ai",
                        false,
                        responseException
                );
            }
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            return new AiCreditIntegrationException(
                    retryable ? "AI_TEMPORARY_ERROR" : "AI_REQUEST_REJECTED",
                    "AI Service từ chối request với HTTP " + status.value(),
                    retryable,
                    responseException
            );
        }
        if (cause instanceof TimeoutException) {
            return new AiCreditIntegrationException(
                    "AI_TIMEOUT", "AI Service không phản hồi trong thời gian cho phép", true, cause);
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

    private AiCreditIntegrationException contractMismatch(String message) {
        return new AiCreditIntegrationException("AI_CONTRACT_MISMATCH", message, false, null);
    }
}
