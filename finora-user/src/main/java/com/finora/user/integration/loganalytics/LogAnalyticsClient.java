package com.finora.user.integration.loganalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.ActivityLogProperties;
import com.finora.user.util.ExternalCallFailures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gọi Log Analytics Query API bằng token managed identity của VM (lấy từ Instance Metadata Service, giữ tới gần lúc
 * hết hạn). Chỉ đọc; quyền là role "Log Analytics Reader" của VM trên workspace {@code finora-logs}.
 * <p>
 * Câu truy vấn đi qua circuit breaker {@value #CIRCUIT_BREAKER}: circuit đang mở thì báo
 * {@code ACTIVITY_LOG_UNAVAILABLE} như khi không kết nối được. Lấy token ở Instance Metadata Service nằm ngoài circuit
 * breaker vì là hệ thống khác.
 */
@Component
public class LogAnalyticsClient {

    public static final String CIRCUIT_BREAKER = "log-analytics";

    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final RestClient restClient;
    private final ActivityLogProperties activityLogProperties;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public LogAnalyticsClient(RestClient.Builder builder, ActivityLogProperties activityLogProperties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this(builder.clone().requestFactory(requestFactory(activityLogProperties)).build(), activityLogProperties,
                circuitBreakerFactory);
    }

    /** Dùng trong test để gắn {@code MockRestServiceServer}. */
    LogAnalyticsClient(RestClient restClient, ActivityLogProperties activityLogProperties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.activityLogProperties = activityLogProperties;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    private static SimpleClientHttpRequestFactory requestFactory(ActivityLogProperties activityLogProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) activityLogProperties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) activityLogProperties.getReadTimeout().toMillis());
        return factory;
    }

    public boolean configured() {
        return activityLogProperties.getWorkspaceId() != null && !activityLogProperties.getWorkspaceId().isBlank();
    }

    /** Chạy một câu KQL, trả các dòng của bảng đầu tiên dưới dạng tên cột → giá trị. */
    public List<Map<String, JsonNode>> query(String kql) {
        if (!configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVITY_LOG_NOT_CONFIGURED",
                    "Nhật ký hoạt động chỉ có trên bản chạy ở Azure (chưa cấu hình Log Analytics)");
        }
        String authorization = "Bearer " + token();
        JsonNode body = circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.post()
                        .uri(activityLogProperties.getQueryUrl(), activityLogProperties.getWorkspaceId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", kql))
                        .retrieve()
                        .body(JsonNode.class),
                failure -> {
                    throw toQueryException(failure);
                });
        return rows(body);
    }

    /** Chạy ở luồng gọi; circuit breaker mở hoặc time limiter cắt được xử lý như không kết nối được. */
    private static RuntimeException toQueryException(Throwable failure) {
        Throwable cause = ExternalCallFailures.cause(failure);
        if (cause instanceof RestClientResponseException rejected) {
            return new BusinessException(HttpStatus.BAD_GATEWAY, "ACTIVITY_LOG_QUERY_FAILED",
                    "Log Analytics từ chối truy vấn (HTTP " + rejected.getStatusCode().value() + ")");
        }
        if (cause instanceof RestClientException || ExternalCallFailures.isUnavailable(cause)) {
            return new BusinessException(HttpStatus.BAD_GATEWAY, "ACTIVITY_LOG_UNAVAILABLE",
                    "Không kết nối được Log Analytics");
        }
        return ExternalCallFailures.unchecked(cause);
    }

    static List<Map<String, JsonNode>> rows(JsonNode body) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        JsonNode table = body == null ? null : body.path("tables").path(0);
        if (table == null || table.isMissingNode()) return rows;
        List<String> columns = new ArrayList<>();
        table.path("columns").forEach(column -> columns.add(column.path("name").asText()));
        for (JsonNode row : table.path("rows")) {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) values.put(columns.get(i), row.path(i));
            rows.add(values);
        }
        return rows;
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return token;
        }
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(activityLogProperties.getTokenUrl())
                    .header("Metadata", "true")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException unavailable) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVITY_LOG_NO_IDENTITY",
                    "Không lấy được danh tính của máy chủ để đọc Log Analytics");
        }
        if (response == null || response.path("access_token").asText("").isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVITY_LOG_NO_IDENTITY",
                    "Không lấy được danh tính của máy chủ để đọc Log Analytics");
        }
        token = response.path("access_token").asText();
        tokenExpiresAt = Instant.ofEpochSecond(response.path("expires_on").asLong(Instant.now().getEpochSecond() + 600));
        return token;
    }
}
