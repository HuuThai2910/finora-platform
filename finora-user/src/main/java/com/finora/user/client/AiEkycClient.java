package com.finora.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Client gọi các endpoint eKYC của {@code finora-ai}.
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

    @PostMapping("/api/v1/ai/ekyc/face-match")
    FaceMatchResult faceMatch(@RequestBody FaceMatchInput input);

    @PostMapping("/api/v1/ai/ekyc/liveness")
    LivenessResult liveness(@RequestBody LivenessInput input);

    @PostMapping("/api/v1/ai/ekyc/liveness-active")
    ActiveLivenessResult activeLiveness(@RequestBody ActiveLivenessInput input);

    // --- DTOs ---

    record OcrInput(String image_base64) {}

    record OcrResult(
        boolean success,
        String id_number,
        String full_name,
        String date_of_birth,
        String gender,
        String place_of_origin,
        double confidence
    ) {}

    record FaceMatchInput(String selfie_base64, String cccd_image_base64) {}

    record FaceMatchResult(boolean match, double similarity, double threshold) {}

    record LivenessInput(String image_base64) {}

    record LivenessResult(boolean is_live, double confidence, String method) {}

    /**
     * @param frames           chuỗi frame base64 theo đúng thứ tự thời gian
     * @param expected_actions chuỗi hành động của phiên challenge, đúng thứ tự
     */
    record ActiveLivenessInput(List<String> frames, List<String> expected_actions) {}

    /** Kết quả kiểm tra một hành động; {@code evidence} dùng để lưu vết và hiển thị. */
    record ActionCheck(String action, boolean passed, String evidence) {}

    /**
     * @param best_frame_index frame nét nhất có mặt chính diện — dùng chính frame này
     *                         cho bước so khớp khuôn mặt thay vì gửi thêm ảnh selfie
     * @param passive_check    kết quả lớp phụ phân tích texture trên vùng mặt
     */
    record ActiveLivenessResult(
        boolean is_live,
        List<ActionCheck> actions,
        double confidence,
        String method,
        Integer best_frame_index,
        LivenessResult passive_check
    ) {}
}
