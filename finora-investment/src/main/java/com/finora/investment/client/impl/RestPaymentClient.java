package com.finora.investment.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.client.PaymentCallException;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentHoldView;
import com.finora.investment.client.PaymentTransferResult;
import com.finora.investment.client.ServiceTokenProvider;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            if (response == null) return PaymentHoldResult.unavailable("Payment không trả kết quả giữ tiền");
            // R3: Payment trả lại khoản giữ cũ của mã lệnh dù trạng thái nào. Chỉ HELD mới là tiền đang được giữ cho lệnh.
            if (response.status() == null || "HELD".equals(response.status())) {
                return PaymentHoldResult.ok(response.holdReference());
            }
            if ("RELEASED".equals(response.status())) return PaymentHoldResult.alreadyReleased(response.holdReference());
            return PaymentHoldResult.notHeld(response.holdReference(), response.status());
        } catch (RestClientResponseException exception) {
            // R3: chỉ lỗi nghiệp vụ là kết luận chắc chắn. Token, quyền, route, giới hạn tần suất (401/403/404/408/429)
            // không chứng minh Payment chưa giữ tiền ở lần gọi trước, nên để lệnh chờ và đối soát lại.
            if (DEFINITIVE_HOLD_REJECTIONS.contains(exception.getStatusCode().value())) {
                return PaymentHoldResult.rejected(errorCode(exception), errorMessage(exception));
            }
            return PaymentHoldResult.unavailable("Payment tạm thời không phản hồi");
        } catch (RuntimeException exception) {
            return PaymentHoldResult.unavailable("Không kết nối được Payment Service");
        }
    }

    @Override
    public Optional<PaymentHoldView> findHold(String orderReference) {
        try {
            HoldBody response = client().get().uri(builder -> builder
                            .path("/transactions/holds")
                            .queryParam("orderReference", orderReference)
                            .build())
                    .headers(this::authorize)
                    .retrieve().body(HoldBody.class);
            if (response == null) throw new PaymentCallException("PAYMENT_UNAVAILABLE", "Payment không trả khoản giữ", true, null);
            return Optional.of(new PaymentHoldView(response.holdReference(), response.amount(), response.status()));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 && "PAYMENT_HOLD_NOT_FOUND".equals(errorCode(exception))) {
                return Optional.empty();
            }
            throw new PaymentCallException(errorCode(exception), "Chưa đọc được khoản giữ ở Payment", true, exception);
        } catch (PaymentCallException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PaymentCallException("PAYMENT_UNAVAILABLE", "Không kết nối được Payment Service", true, exception);
        }
    }

    @Override
    public void release(String holdReference, String orderReference) {
        try {
            client().post().uri(builder -> builder
                            .path("/transactions/holds/{holdReference}/release")
                            .queryParam("orderReference", orderReference)
                            .build(holdReference))
                    .headers(this::authorize)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            String code = errorCode(exception);
            // Khoản giữ đã vào giải ngân, không tồn tại hoặc lệch mã lệnh/chủ: thử lại không đổi được kết quả (R3).
            boolean definitive = DEFINITIVE_RELEASE_FAILURES.contains(code);
            throw new PaymentCallException(code, "Payment chưa nhả khoản giữ " + holdReference, !definitive, exception);
        } catch (RuntimeException exception) {
            throw new PaymentCallException("PAYMENT_UNAVAILABLE", "Không kết nối được Payment Service", true, exception);
        }
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

    @Override
    public PaymentTransferResult transferBetweenInvestors(
            String fromInvestorId, String toInvestorId, BigDecimal amount, String transferReference) {
        try {
            TransferBody response = client().post().uri("/transactions/transfers")
                    .headers(this::authorize)
                    .body(Map.of(
                            "buyerId", fromInvestorId, "sellerId", toInvestorId, "price", amount,
                            "platformFee", BigDecimal.ZERO, "transferReference", transferReference))
                    .retrieve().body(TransferBody.class);
            return response == null
                    ? PaymentTransferResult.unavailable("Payment không trả kết quả chuyển tiền")
                    : PaymentTransferResult.ok(response.paymentReference());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return PaymentTransferResult.rejected(errorCode(exception), errorMessage(exception));
            }
            return PaymentTransferResult.unavailable("Payment tạm thời không chuyển được tiền");
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

    /** Lỗi nghiệp vụ của lời gọi giữ tiền: số dư, dữ liệu không hợp lệ, mã lệnh đã dùng với nội dung khác. */
    private static final Set<Integer> DEFINITIVE_HOLD_REJECTIONS = Set.of(400, 409, 422);

    private static final Set<String> DEFINITIVE_RELEASE_FAILURES = Set.of(
            "PAYMENT_HOLD_NOT_RELEASABLE", "PAYMENT_HOLD_NOT_FOUND", "PAYMENT_HOLD_ORDER_MISMATCH", "PAYMENT_OWNER_MISMATCH");

    private record HoldBody(String holdReference, BigDecimal amount, String status) {}
    private record TransferBody(String paymentReference) {}
}
