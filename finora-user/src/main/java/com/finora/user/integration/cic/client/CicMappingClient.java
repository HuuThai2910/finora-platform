package com.finora.user.integration.cic.client;

import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import com.finora.user.util.ExternalCallFailures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Client gọi CIC để đăng ký ánh xạ người vay ({@code user_profiles.id}) sang số CCCD. Xác thực bằng header
 * {@code X-Finora-Internal-Key}; cố ý không gắn interceptor token service account
 * ({@code ServiceAccountConfig.BEARER_INTERCEPTOR}) để token của finora-user không bị gửi ra ngoài.
 * <p>
 * Timeout kết nối 10 giây, đọc 60 giây (đúng mặc định của Feign trước đây). Lỗi được ném thành
 * {@link CicMappingException} mang mã HTTP, hoặc {@value #NO_RESPONSE_STATUS} khi không nhận được phản hồi (lỗi mạng,
 * quá thời gian chờ, circuit breaker {@value #CIRCUIT_BREAKER} đang mở). Không đưa thân request/response vào thông
 * điệp lỗi vì có thể chứa CCCD.
 */
@Component
public class CicMappingClient {

    public static final String CIRCUIT_BREAKER = "cic";

    /** Mã trạng thái khi không nhận được phản hồi HTTP (giữ đúng quy ước {@code status() == -1} của Feign cũ). */
    public static final int NO_RESPONSE_STATUS = -1;

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    static final String PATH = "/api/v1/internal/borrower-mappings";
    static final String INTERNAL_KEY_HEADER = "X-Finora-Internal-Key";

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    public CicMappingClient(RestClient.Builder builder, @Value("${finora.cic.url:http://localhost:9000}") String cicUrl,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this(builder.clone()
                .baseUrl(cicUrl)
                .requestFactory(requestFactory())
                .build(), circuitBreakerFactory);
    }

    /** Dùng trong test để gắn {@code MockRestServiceServer}. */
    CicMappingClient(RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    /**
     * Đăng ký ánh xạ người vay sang CCCD.
     *
     * @throws CicMappingException CIC trả lỗi HTTP hoặc không phục vụ được
     */
    public void register(String internalApiKey, RegisterBorrowerMappingRequest request) {
        circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.post()
                        .uri(PATH)
                        .header(INTERNAL_KEY_HEADER, internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity(),
                failure -> {
                    throw toCicMappingException(failure);
                });
    }

    /** Chạy ở luồng gọi: lỗi HTTP giữ mã trạng thái; không có phản hồi (kể cả circuit mở, quá giờ) thành -1. */
    private static RuntimeException toCicMappingException(Throwable failure) {
        Throwable cause = ExternalCallFailures.cause(failure);
        if (cause instanceof RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            return new CicMappingException("CIC trả HTTP " + status, status);
        }
        if (cause instanceof RestClientException || ExternalCallFailures.isUnavailable(cause)) {
            return new CicMappingException("Không gọi được CIC: " + cause.getClass().getSimpleName(),
                    NO_RESPONSE_STATUS);
        }
        return ExternalCallFailures.unchecked(cause);
    }

    /**
     * Lời gọi CIC thất bại. {@link #getStatus()} là mã HTTP CIC trả, hoặc {@value CicMappingClient#NO_RESPONSE_STATUS}
     * khi không nhận được phản hồi; worker dùng nó để quyết định hẹn chạy lại.
     */
    public static class CicMappingException extends RuntimeException {
        private final int status;

        public CicMappingException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
