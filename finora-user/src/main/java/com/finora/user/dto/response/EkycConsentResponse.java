package com.finora.user.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Điều khoản eKYC hiện hành và trạng thái đồng ý của người dùng (EKYC-002 mục 3.1).
 *
 * @param version      phiên bản điều khoản
 * @param title        tiêu đề hiển thị
 * @param body         nội dung Markdown đã điền chỗ trống — đúng nội dung được băm
 * @param consentLabel câu cạnh ô tick duy nhất
 * @param purposes     mã mục đích ghi vào bản ghi đồng ý
 * @param textSha256   SHA-256 (hex) của {@code body}, client gửi lại khi đồng ý
 * @param accepted     đã có đồng ý còn hiệu lực với đúng phiên bản và nội dung này
 * @param acceptedAt   thời điểm đồng ý còn hiệu lực, {@code null} nếu chưa đồng ý
 */
public record EkycConsentResponse(
        String version,
        String title,
        String body,
        String consentLabel,
        List<String> purposes,
        String textSha256,
        boolean accepted,
        Instant acceptedAt
) {
}
