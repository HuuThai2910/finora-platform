package com.finora.payment.integration.topup.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.config.ZaloPayProperties;
import com.finora.payment.integration.topup.ZaloPayMac;
import com.finora.payment.integration.topup.client.TopUpProvider;
import com.finora.payment.util.CircuitBreakerCalls;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tạo đơn nạp tiền trên ZaloPay ({@code POST /v2/create}).
 * <p>
 * Lời gọi HTTP đi qua circuit breaker {@code zalopay-topup}; circuit mở được báo như mất kết nối nên lệnh nạp vẫn ghi
 * {@code TOPUP_PROVIDER_UNCERTAIN} như khi ZaloPay sập. Kiểm tra {@code return_code} nằm ngoài circuit breaker để đơn
 * bị ZaloPay từ chối không bị tính là sự cố của ZaloPay.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.payment.top-up", name = "provider", havingValue = "zalopay")
public class ZaloPayTopUpProvider implements TopUpProvider {
    /** Tên circuit breaker và time limiter trong {@code application.yml}. */
    private static final String CIRCUIT_BREAKER = "zalopay-topup";
    private final ZaloPayProperties zaloPayProperties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Override
    public String name() {
        return "ZALOPAY";
    }

    @Override
    public TopUpProviderResult create(TopUpProviderCommand command) {
        zaloPayProperties.requireConfigured();
        long appTime = clock.millis();
        String item = "[]";
        String embedData = embedData();
        String amount = command.amount().toBigIntegerExact().toString();
        String appId = Integer.toString(zaloPayProperties.requiredAppId());
        String macInput = String.join("|", appId, command.providerOrderId(), command.ownerId(),
                amount, Long.toString(appTime), embedData, item);

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("app_id", appId);
        form.add("app_user", command.ownerId());
        form.add("app_time", Long.toString(appTime));
        form.add("amount", amount);
        form.add("app_trans_id", command.providerOrderId());
        form.add("embed_data", embedData);
        form.add("item", item);
        form.add("description", "FINORA nap tien " + command.providerOrderId());
        form.add("bank_code", "");
        form.add("callback_url", zaloPayProperties.callbackUrl());
        form.add("mac", ZaloPayMac.hmacSha256(macInput, zaloPayProperties.key1()));

        RestClient restClient = client();
        JsonNode response = CircuitBreakerCalls.run(circuitBreakerFactory, CIRCUIT_BREAKER, "ZaloPay",
                () -> restClient
                        .post().uri("/v2/create")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class));
        if (response == null || response.path("return_code").asInt() != 1) {
            String message = response == null ? "ZaloPay không trả dữ liệu"
                    : response.path("return_message").asText("ZaloPay từ chối tạo đơn");
            throw new IllegalStateException(message);
        }
        String checkout = text(response, "order_url");
        String qr = text(response, "qr_code");
        if (qr == null) qr = text(response, "order_token");
        return new TopUpProviderResult(checkout, qr, clock.instant().plus(Duration.ofMinutes(15)));
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(zaloPayProperties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(zaloPayProperties.readTimeout().toMillis()));
        return restClientBuilder.requestFactory(requestFactory).baseUrl(zaloPayProperties.baseUrl()).build();
    }

    private String embedData() {
        try {
            Map<String, String> embedData = new LinkedHashMap<>();
            if (zaloPayProperties.redirectUrl() != null && !zaloPayProperties.redirectUrl().isBlank()) {
                embedData.put("redirecturl", zaloPayProperties.redirectUrl());
            }
            return objectMapper.writeValueAsString(embedData);
        } catch (Exception exception) {
            throw new IllegalStateException("Không tạo được embed_data ZaloPay", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
