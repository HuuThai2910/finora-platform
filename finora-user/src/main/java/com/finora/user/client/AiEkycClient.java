package com.finora.user.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * Client gọi bước đọc thẻ của {@code finora-ai}: {@code POST /api/v1/ai/ekyc/document} (hợp đồng
 * EKYC-002 mục 2.1). Route chỉ gọi nội bộ, phải kèm header {@value #INTERNAL_TOKEN_HEADER}; gateway
 * chặn mọi request từ ngoài vào {@code /api/v1/ai/ekyc/**}.
 * <p>
 * AI đọc hai mặt thẻ theo thứ tự nguồn hỏng-thì-lộ (QR, MRZ có check digit, cấu trúc số định danh;
 * AI chỉ điền chỗ trống) và trả bằng chứng kỹ thuật kèm {@code decision}. Luật nghiệp vụ — hết hạn,
 * dưới 18 tuổi, CMND, trùng số — và trạng thái KYC do User quyết định, không nằm ở AI.
 * <p>
 * DTO dùng tên camelCase trong Java và {@link JsonNaming snake_case} trên dây để khớp FastAPI; đổi
 * chiến lược đặt tên sẽ làm lệch hợp đồng giữa hai service. Lỗi HTTP được Feign ném thành
 * {@code FeignException} (422 ảnh không hợp lệ, 401 sai token, 503 AI bận).
 */
@FeignClient(
        name = "ai-ekyc-service",
        url = "${finora.ai.url:http://localhost:8000}"
)
public interface AiEkycClient {

    String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @PostMapping("/api/v1/ai/ekyc/document")
    DocumentResult analyzeDocument(
            @RequestHeader(INTERNAL_TOKEN_HEADER) String internalToken,
            @RequestBody DocumentRequest request);

    // --- DTOs ---

    /**
     * @param qrText     chuỗi QR mobile đã quét được, {@code null} nếu chưa có (AI tự giải trên ảnh)
     * @param qrSide     mặt thẻ chứa QR mà mobile đã quét: {@code FRONT|BACK}
     * @param retakeCount số lần người dùng đã phải chụp lại trong giờ qua — AI nới quyết định sau 2 lần
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DocumentRequest(
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
    record DocumentResult(
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
    record RetakeHint(String side, String reason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocumentFields(
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
    record FieldReading(String value, List<String> sources, Boolean noExpiry) {
    }

    /** Ảnh chân dung trên mặt trước; {@code embeddingB64} là 128 float32 đã chuẩn hoá L2 (dữ liệu sinh trắc). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Portrait(Boolean found, Double iedPx, Double sharpness, String embeddingB64) {
    }
}
