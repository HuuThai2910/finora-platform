package com.finora.user.integration.ai.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.finora.user.config.AiServiceProperties;
import com.finora.user.config.ServiceAccountConfig;
import com.finora.user.util.ExternalCallFailures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Client gọi bước đọc thẻ của {@code finora-ai}: {@code POST /api/v1/ai/ekyc/document} (hợp đồng
 * EKYC-002 mục 2.1). Route chỉ nhận access token service account của finora-user (client role
 * {@code ai:ekyc:ocr}, D34/D35) do interceptor {@link ServiceAccountConfig#BEARER_INTERCEPTOR} gắn; gateway chặn mọi
 * request từ ngoài vào {@code /api/v1/ai/ekyc/**}.
 * <p>
 * AI đọc hai mặt thẻ theo thứ tự nguồn hỏng-thì-lộ (QR, MRZ có check digit, cấu trúc số định danh;
 * AI chỉ điền chỗ trống) và trả bằng chứng kỹ thuật kèm {@code decision}. Luật nghiệp vụ — hết hạn,
 * dưới 18 tuổi, CMND, trùng số — và trạng thái KYC do User quyết định, không nằm ở AI.
 * <p>
 * DTO dùng tên camelCase trong Java và {@link JsonNaming snake_case} trên dây để khớp FastAPI; đổi
 * chiến lược đặt tên sẽ làm lệch hợp đồng giữa hai service. Lỗi được ném thành {@link AiEkycException} mang mã HTTP
 * (422 ảnh không hợp lệ, 401/403 token service account, 503 AI bận), hoặc {@value #NO_RESPONSE_STATUS} khi không nhận
 * được phản hồi (lỗi mạng, quá thời gian chờ, không đọc được phản hồi, circuit breaker {@value #CIRCUIT_BREAKER} đang
 * mở).
 */
@Component
public class AiEkycClient {

    /** Circuit breaker chung cho mọi lời gọi finora-ai (đọc thẻ và liveness). */
    public static final String CIRCUIT_BREAKER = "ai";

    /** Mã trạng thái khi không nhận được phản hồi HTTP hợp lệ (giữ đúng quy ước {@code status() == -1} của Feign cũ). */
    public static final int NO_RESPONSE_STATUS = -1;

    static final String PATH = "/api/v1/ai/ekyc/document";

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    public AiEkycClient(RestClient.Builder builder, AiServiceProperties aiServiceProperties,
            @Qualifier(ServiceAccountConfig.BEARER_INTERCEPTOR) ClientHttpRequestInterceptor serviceAccountBearerInterceptor,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this(builder.clone()
                .baseUrl(aiServiceProperties.getUrl())
                .requestFactory(requestFactory(aiServiceProperties))
                .requestInterceptor(serviceAccountBearerInterceptor)
                .build(), circuitBreakerFactory);
    }

    /** Dùng trong test để gắn {@code MockRestServiceServer}. */
    AiEkycClient(RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    private static SimpleClientHttpRequestFactory requestFactory(AiServiceProperties aiServiceProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) aiServiceProperties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) aiServiceProperties.getDocumentReadTimeout().toMillis());
        return factory;
    }

    /**
     * Gửi hai mặt thẻ (base64) để AI đọc.
     *
     * @return kết quả của AI; {@code null} nếu AI trả thân rỗng
     * @throws AiEkycException AI trả lỗi HTTP hoặc không phục vụ được
     */
    public DocumentResult analyzeDocument(DocumentRequest request) {
        return circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.post()
                        .uri(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(DocumentResult.class),
                failure -> {
                    throw toAiEkycException(failure);
                });
    }

    /** Chạy ở luồng gọi: lỗi HTTP giữ mã trạng thái; không có phản hồi (kể cả circuit mở, quá giờ) thành -1. */
    private static RuntimeException toAiEkycException(Throwable failure) {
        Throwable cause = ExternalCallFailures.cause(failure);
        if (cause instanceof RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            return new AiEkycException("AI trả HTTP " + status + " khi đọc thẻ", status);
        }
        if (cause instanceof RestClientException || ExternalCallFailures.isUnavailable(cause)) {
            return new AiEkycException("Không gọi được AI đọc thẻ: " + cause.getClass().getSimpleName(),
                    NO_RESPONSE_STATUS);
        }
        return ExternalCallFailures.unchecked(cause);
    }

    // --- DTOs ---

    /**
     * @param qrText     chuỗi QR mobile đã quét được, {@code null} nếu chưa có (AI tự giải trên ảnh)
     * @param qrSide     mặt thẻ chứa QR mà mobile đã quét: {@code FRONT|BACK}
     * @param retakeCount số lần người dùng đã phải chụp lại trong giờ qua — AI nới quyết định sau 2 lần
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DocumentRequest(
            String frontImageBase64,
            String backImageBase64,
            String qrText,
            String qrSide,
            String frontSource,
            String backSource,
            int retakeCount
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentResult(
            String decision,
            String cardType,
            List<String> reasons,
            List<String> warnings,
            List<RetakeHint> retake,
            DocumentFields fields,
            JsonNode checks,
            JsonNode quality,
            Portrait portrait,
            Boolean aiUsed,
            String thresholdsVersion
    ) {
    }

    /** Mặt thẻ cần chụp lại và lý do ({@code BLUR}, {@code GLARE}, {@code MRZ_UNREADABLE}…). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetakeHint(String side, String reason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentFields(
            FieldReading idNumber,
            FieldReading fullName,
            FieldReading dateOfBirth,
            FieldReading gender,
            FieldReading address,
            FieldReading placeOfOrigin,
            FieldReading birthRegistrationPlace,
            FieldReading issueDate,
            FieldReading expiryDate
    ) {
    }

    /**
     * Một trường đọc được kèm nguồn ({@code QR}, {@code MRZ}, {@code OCR}, {@code AI}).
     * {@code noExpiry} chỉ có ở {@code expiry_date}: thẻ ghi "không thời hạn".
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldReading(String value, List<String> sources, Boolean noExpiry) {
    }

    /** Ảnh chân dung trên mặt trước; {@code embeddingB64} là 128 float32 đã chuẩn hoá L2 (dữ liệu sinh trắc). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Portrait(Boolean found, Double iedPx, Double sharpness, String embeddingB64) {
    }

    /**
     * Lời gọi đọc thẻ thất bại. {@link #getStatus()} là mã HTTP AI trả, hoặc {@value AiEkycClient#NO_RESPONSE_STATUS}
     * khi không nhận được phản hồi; nơi gọi dùng nó để tách ảnh không hợp lệ (400/422) với AI không phục vụ được.
     */
    public static class AiEkycException extends RuntimeException {
        private final int status;

        public AiEkycException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
