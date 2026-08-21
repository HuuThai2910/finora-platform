package com.finora.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Client gọi endpoint eKYC của {@code finora-ai}.
 * <p>
 * Luồng hiện tại chỉ dùng OCR mặt trước CCCD; các endpoint face-match/liveness
 * phía AI vẫn tồn tại nhưng không còn được gọi từ service này.
 * <p>
 * Tên field trong DTO giữ nguyên snake_case theo hợp đồng JSON của FastAPI —
 * đổi sang camelCase sẽ làm lệch hợp đồng giữa hai service.
 * <p>
 * AI chỉ trả bằng chứng kỹ thuật; quyết định trạng thái KYC thuộc về User service.
 */
@FeignClient(
    name = "ai-ekyc-service",
    url = "${finora.ai.url:http://localhost:8000}"
)
public interface AiEkycClient {

    @PostMapping("/api/v1/ai/ekyc/ocr")
    OcrResult ocr(@RequestBody OcrInput input);

    // --- DTOs ---

    record OcrInput(String image_base64) {}

    record OcrResult(
        boolean success,
        String id_number,
        String full_name,
        String date_of_birth,
        String gender,
        String place_of_origin,
        String address,
        double confidence
    ) {}
}
