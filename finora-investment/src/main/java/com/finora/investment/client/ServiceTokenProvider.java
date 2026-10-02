package com.finora.investment.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Access token của service account {@code finora-investment-client} (client_credentials).
 *
 * <p>Dùng khi Investment gọi Payment mà không có JWT của người dùng — hiện chỉ có Auto-Invest
 * chạy trong worker. Token mang client role {@code payment:hold:on_behalf}, nên Payment cho
 * phép giữ/nhả tiền thay nhà đầu tư. Cache tới trước lúc hết hạn {@link #REFRESH_MARGIN}.</p>
 */
@Slf4j
@Component
public class ServiceTokenProvider {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final Clock clock;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(
            RestClient.Builder restClientBuilder,
            Clock clock,
            @Value("${finora.investment.service-account.token-url:http://localhost:8180/realms/finora/protocol/openid-connect/token}")
            String tokenUrl,
            @Value("${finora.investment.service-account.client-id:finora-investment-client}") String clientId,
            @Value("${finora.investment.service-account.client-secret:}") String clientSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.clock = clock;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(expiresAt.minus(REFRESH_MARGIN))) {
            return cachedToken;
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu KEYCLOAK_INVESTMENT_CLIENT_SECRET — Auto-Invest không giữ tiền được");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenBody body = restClient.post().uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenBody.class);
        if (body == null || body.accessToken() == null) {
            throw new IllegalStateException("Keycloak không trả access token cho " + clientId);
        }
        cachedToken = body.accessToken();
        expiresAt = now.plusSeconds(body.expiresIn());
        return cachedToken;
    }

    private record TokenBody(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}
