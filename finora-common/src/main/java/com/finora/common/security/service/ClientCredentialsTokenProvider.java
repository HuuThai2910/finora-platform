package com.finora.common.security.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Access token của service account Keycloak (grant {@code client_credentials}) cho lời gọi service → service không có
 * JWT của người dùng (D34): Loan chấm điểm qua AI trong worker, User gửi email qua Notification khi người dùng chưa đăng
 * nhập. Service nhận kiểm client role trong token thay vì tin bất kỳ ai gọi được cổng mạng nội bộ.
 * <p>
 * Token được cache tới trước lúc hết hạn {@link #REFRESH_MARGIN}; không log token hay secret. Không phải Spring bean:
 * mỗi service tự tạo với client của mình để token không bị gửi nhầm tới dịch vụ bên ngoài.
 */
@Slf4j
public class ClientCredentialsTokenProvider {

    static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final Clock clock;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(RestClient.Builder restClientBuilder, Clock clock, String tokenUrl,
            String clientId, String clientSecret) {
        this.restClient = restClientBuilder.build();
        this.clock = clock;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * @throws ServiceTokenException khi thiếu secret hoặc Keycloak không cấp token; lời gọi phía sau coi như dependency
     *         tạm lỗi (retry theo cơ chế của service gọi), không phải lỗi nghiệp vụ
     */
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(expiresAt.minus(REFRESH_MARGIN))) {
            return cachedToken;
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new ServiceTokenException("Thiếu secret của service account " + clientId, null);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenBody body;
        try {
            body = restClient.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(TokenBody.class);
        } catch (RestClientException exception) {
            log.warn("Không lấy được token service account: clientId={}, error={}", clientId,
                    exception.getClass().getSimpleName());
            throw new ServiceTokenException("Keycloak không cấp token cho " + clientId, exception);
        }
        if (body == null || body.accessToken() == null || body.accessToken().isBlank()) {
            throw new ServiceTokenException("Keycloak không trả access token cho " + clientId, null);
        }
        cachedToken = body.accessToken();
        expiresAt = now.plusSeconds(Math.max(body.expiresIn(), 0));
        return cachedToken;
    }

    /** Bỏ token đang cache, ví dụ khi service nhận trả 401 vì Keycloak đã đổi khóa ký. */
    public synchronized void invalidate() {
        cachedToken = null;
        expiresAt = Instant.EPOCH;
    }

    /**
     * Interceptor gắn {@code Authorization: Bearer} cho một {@code RestClient}. Không lấy được token thì ném
     * {@link IOException} để {@code RestClient} báo {@code ResourceAccessException} như mất kết nối: service gọi xử lý
     * như dependency tạm lỗi thay vì lỗi không xác định. Service nhận trả 401 thì bỏ token đang cache để lần thử lại
     * lấy token mới.
     */
    public ClientHttpRequestInterceptor bearerInterceptor() {
        return (request, body, execution) -> {
            String token;
            try {
                token = accessToken();
            } catch (ServiceTokenException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
            request.getHeaders().setBearerAuth(token);
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().value() == 401) {
                invalidate();
            }
            return response;
        };
    }

    public String clientId() {
        return clientId;
    }

    private record TokenBody(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }

    /** Không có token service account; thông điệp chỉ chứa client id, không chứa secret. */
    public static class ServiceTokenException extends RuntimeException {
        public ServiceTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
