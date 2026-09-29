package com.finora.investment.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentTransferResult;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.payment", name = "mode", havingValue = "http", matchIfMissing = true)
public class RestPaymentClient implements PaymentClient {
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${finora.investment.payment.base-url:http://localhost:8082/api/v1}")
    private String baseUrl;

    @Override
    public PaymentHoldResult hold(String investorId, BigDecimal amount, String orderReference) {
        try {
            HoldBody response = client().post().uri("/transactions/holds")
                    .headers(this::authorize)
                    .body(Map.of("investorId", investorId, "amount", amount, "orderReference", orderReference))
                    .retrieve().body(HoldBody.class);
            return response == null
                    ? PaymentHoldResult.unavailable("Payment không trả kết quả giữ tiền")
                    : PaymentHoldResult.ok(response.holdReference());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return PaymentHoldResult.rejected(errorCode(exception), errorMessage(exception));
            }
            return PaymentHoldResult.unavailable("Payment tạm thời không phản hồi");
        } catch (RuntimeException exception) {
            return PaymentHoldResult.unavailable("Không kết nối được Payment Service");
        }
    }

    @Override
    public void release(String holdReference, String orderReference) {
        client().post().uri(builder -> builder
                        .path("/transactions/holds/{holdReference}/release")
                        .queryParam("orderReference", orderReference)
                        .build(holdReference))
                .headers(this::authorize)
                .retrieve().toBodilessEntity();
    }

    @Override
    public PaymentTransferResult transfer(
            String buyerId,
            String sellerId,
            BigDecimal price,
            BigDecimal platformFee,
            String transferReference
    ) {
        try {
            TransferBody response = client().post().uri("/transactions/transfers")
                    .headers(this::authorize)
                    .body(Map.of(
                            "buyerId", buyerId, "sellerId", sellerId, "price", price,
                            "platformFee", platformFee, "transferReference", transferReference))
                    .retrieve().body(TransferBody.class);
            return response == null
                    ? PaymentTransferResult.unavailable("Payment không trả kết quả chuyển tiền")
                    : PaymentTransferResult.ok(response.paymentReference());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return PaymentTransferResult.rejected(errorCode(exception), errorMessage(exception));
            }
            return PaymentTransferResult.unavailable("Payment tạm thời không phản hồi");
        } catch (RuntimeException exception) {
            return PaymentTransferResult.unavailable("Không kết nối được Payment Service");
        }
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(SecurityUtils.getJwt().getTokenValue());
    }

    private String errorCode(RestClientResponseException exception) {
        try {
            JsonNode node = objectMapper.readTree(exception.getResponseBodyAsString());
            return node.path("code").asText("PAYMENT_REJECTED");
        } catch (Exception ignored) {
            return "PAYMENT_REJECTED";
        }
    }

    private String errorMessage(RestClientResponseException exception) {
        try {
            JsonNode node = objectMapper.readTree(exception.getResponseBodyAsString());
            return node.path("message").asText("Payment từ chối giao dịch");
        } catch (Exception ignored) {
            return "Payment từ chối giao dịch";
        }
    }

    private record HoldBody(String holdReference) {}
    private record TransferBody(String paymentReference) {}
}
