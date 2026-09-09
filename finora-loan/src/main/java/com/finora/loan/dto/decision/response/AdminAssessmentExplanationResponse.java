package com.finora.loan.dto.decision.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Phần giải thích của lần chấm điểm đã dùng để quyết định hồ sơ.
 *
 * <p>Dữ liệu lấy nguyên từ {@code response_snapshot_json} mà AI Service trả về lúc
 * chấm, không chấm lại. Chấm lại sẽ cho con số khác khi mô hình hoặc bộ luật đã đổi,
 * và như vậy màn hình không còn là bằng chứng cho quyết định đã ra.</p>
 *
 * <p>Ba khối giải thích giữ nguyên cấu trúc của AI thay vì ánh xạ thành record Java:
 * chúng là nội dung để đọc, Loan không diễn giải cũng không ràng buộc nghiệp vụ lên
 * chúng, nên ánh xạ chi tiết chỉ tạo thêm một chỗ phải sửa mỗi lần AI đổi định dạng.</p>
 */
public record AdminAssessmentExplanationResponse(
        Long assessmentId,
        String actualModelVersion,
        String decisionPolicyVersion,
        Instant scoredAt,
        /** Bản giải thích cho người đọc: mức độ, yếu tố có lợi/bất lợi, gợi ý cải thiện. */
        JsonNode borrowerExplanation,
        /** Đóng góp SHAP của từng đặc trưng vào xác suất vỡ nợ. */
        JsonNode modelExplanation,
        /** Vết chấm của từng luật trong bộ luật đang bật, kèm luật thiếu dữ liệu. */
        JsonNode ruleTrace
) {
}
