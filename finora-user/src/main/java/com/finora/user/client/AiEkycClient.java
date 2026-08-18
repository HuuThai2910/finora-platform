package com.finora.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
}
