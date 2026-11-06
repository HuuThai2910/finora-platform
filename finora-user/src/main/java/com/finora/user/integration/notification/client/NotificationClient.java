package com.finora.user.integration.notification.client;

import com.finora.user.config.ServiceAccountConfig;
import com.finora.user.integration.notification.contract.OtpEmailRequest;
import com.finora.user.integration.notification.contract.SuspiciousActivityAlertRequest;
import com.finora.user.integration.notification.contract.WelcomeEmailRequest;
import com.finora.user.util.ExternalCallFailures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Client gọi finora-notification để gửi email.
 * <p>
 * Thay thế Kafka producer — giao tiếp đồng bộ qua HTTP.
 * Sau này nếu cần async, swap lại Kafka mà không đổi interface service layer.
 * <p>
 * Endpoint nội bộ của Notification chỉ nhận token có {@code notification:internal:send} (D34, D36): token service
 * account do interceptor {@link ServiceAccountConfig#BEARER_INTERCEPTOR} gắn. Timeout kết nối 10 giây, đọc 60 giây
 * (đúng mặc định của Feign trước đây). Lỗi HTTP và lỗi mạng giữ nguyên exception của {@link RestClient}; circuit breaker
 * {@value #CIRCUIT_BREAKER} đang mở hoặc quá giờ thì ném {@link ResourceAccessException} như khi mất kết nối. Nơi gọi
 * gửi best-effort nên chỉ log.
 */
@Component
public class NotificationClient {

    public static final String CIRCUIT_BREAKER = "notification";

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    public NotificationClient(RestClient.Builder builder,
            @Value("${finora.notification.url:http://localhost:8086}") String notificationUrl,
            @Qualifier(ServiceAccountConfig.BEARER_INTERCEPTOR) ClientHttpRequestInterceptor serviceAccountBearerInterceptor,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this(builder.clone()
                .baseUrl(notificationUrl)
                .requestFactory(requestFactory())
                .requestInterceptor(serviceAccountBearerInterceptor)
                .build(), circuitBreakerFactory);
    }

    /** Dùng trong test để gắn {@code MockRestServiceServer}. */
    NotificationClient(RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    public void sendWelcomeEmail(WelcomeEmailRequest request) {
        post("/api/internal/notifications/welcome-email", request);
    }

    public void sendOtpEmail(OtpEmailRequest request) {
        post("/api/internal/notifications/otp-email", request);
    }

    public void sendSuspiciousActivityAlert(SuspiciousActivityAlertRequest request) {
        post("/api/internal/notifications/suspicious-activity-alert", request);
    }

    private void post(String path, Object request) {
        circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity(),
                failure -> {
                    throw ExternalCallFailures.asRestClientException(failure, "finora-notification");
                });
    }
}
