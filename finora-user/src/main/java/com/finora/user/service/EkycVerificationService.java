package com.finora.user.service;

import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Bước quét thẻ và bước xác nhận của eKYC (EKYC-002 mục 3.2, 3.5).
 */
public interface EkycVerificationService {

    /** Ngày hết hạn và tuổi tính theo lịch Việt Nam, không theo UTC của server. */
    ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    EkycResultResponse verify(UUID keycloakUserId, EkycVerifyRequest request);

    /** Như {@link #confirm(UUID, Map, boolean)} với {@code reportIncorrect = false}. */
    default EkycResultResponse confirm(UUID keycloakUserId, Map<String, String> edits) {
        return confirm(keycloakUserId, edits, false);
    }

    /**
     * Bước xác nhận bản nháp. {@code reportIncorrect}: người dùng báo thông tin đọc từ thẻ chưa đúng — bỏ qua
     * {@code edits}, hồ sơ luôn chuyển duyệt tay kèm mã {@code USER_REPORTED_INCORRECT}.
     */
    EkycResultResponse confirm(UUID keycloakUserId, Map<String, String> edits, boolean reportIncorrect);

    /**
     * AI lỗi đủ số lần liên tiếp ở bước thẻ: nhận lại hai ảnh, lưu làm bằng chứng và mở lượt với bản nháp rỗng
     * (không gọi AI). Trả {@code DRAFT_READY} + {@code manualReviewMode}; bước liveness sau đó gửi thẳng nhân viên.
     * Chưa đủ ngưỡng → {@code 409 MANUAL_REVIEW_UNAVAILABLE}.
     */
    EkycResultResponse startManualReview(UUID keycloakUserId, EkycVerifyRequest request);
}
