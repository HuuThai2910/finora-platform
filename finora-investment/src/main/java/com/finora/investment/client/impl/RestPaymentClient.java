package com.finora.investment.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentTransferResult;
import com.finora.investment.client.ServiceTokenProvider;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.payment", name = "mode", havingValue = "http", matchIfMissing = true)
public class RestPaymentClient implements PaymentClient {
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final ServiceTokenProvider serviceTokenProvider;

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

    /**
     * Thanh toán một lần khớp từ tiền đang giữ.
     *
     * <p>Endpoint {@code /holds/{holdReference}/settlements} là hợp đồng mới đề xuất cho
     * finora-payment (xem {@code docs/integrations/INVESTMENT-PAYMENT-ORDER-BOOK.md}). Khi Payment
     * chưa triển khai, server trả 404/405 không mang mã nghiệp vụ; coi đó là lỗi tạm thời để lần
     * khớp nằm chờ và tự thanh toán khi Payment lên bản mới, thay vì đánh dấu thất bại vĩnh viễn.</p>
     */
    @Override
    public PaymentTransferResult settleFromHold(
            String holdReference,
            String orderReference,
            String sellerId,
            BigDecimal amount,
            BigDecimal platformFee,
            String settlementReference
    ) {
        try {
            TransferBody response = client().post().uri("/transactions/holds/{holdReference}/settlements", holdReference)
                    .headers(this::authorize)
                    .body(Map.of(
                            "orderReference", orderReference, "sellerId", sellerId, "amount", amount,
                            "platformFee", platformFee, "settlementReference", settlementReference))
                    .retrieve().body(TransferBody.class);
            return response == null
                    ? PaymentTransferResult.unavailable("Payment không trả kết quả thanh toán")
                    : PaymentTransferResult.ok(response.paymentReference());
        } catch (RestClientResponseException exception) {
            String code = errorCode(exception);
            boolean endpointMissing = (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 405)
                    && "PAYMENT_REJECTED".equals(code);
            if (exception.getStatusCode().is4xxClientError() && !endpointMissing) {
                return PaymentTransferResult.rejected(code, errorMessage(exception));
            }
            return PaymentTransferResult.unavailable("Payment tạm thời không thanh toán được");
        } catch (RuntimeException exception) {
            return PaymentTransferResult.unavailable("Không kết nối được Payment Service");
        }
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * Đang phục vụ request của người dùng thì chuyển tiếp JWT của họ; không có (worker
     * Auto-Invest) thì dùng token service account. Payment tự quyết token nào được làm gì —
     * token người dùng không bao giờ mang quyền giữ tiền thay người khác.
     */
    private void authorize(HttpHeaders headers) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            headers.setBearerAuth(SecurityUtils.getJwt().getTokenValue());
        } else {
            headers.setBearerAuth(serviceTokenProvider.accessToken());
        }
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
