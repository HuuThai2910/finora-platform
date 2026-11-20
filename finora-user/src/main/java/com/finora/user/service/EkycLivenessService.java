package com.finora.user.service;

import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;

import java.util.List;
import java.util.UUID;

/**
 * Kiểm tra khuôn mặt (liveness + so khớp với ảnh chân dung trên thẻ) — EKYC-002 mục 3.4.
 */
public interface EkycLivenessService {

    LivenessChallengeResponse createChallenge(UUID keycloakUserId);

    EkycResultResponse submit(UUID keycloakUserId, LivenessUpload upload);

    /**
     * Dữ liệu multipart đã đọc ở controller (không phụ thuộc kiểu HTTP).
     *
     * @param frameTimesMs mảng JSON mốc thời gian (ms) của từng ảnh ở chế độ STILLS
     * @param reviewMode   {@code MANUAL}: AI đang lỗi, chỉ lưu video làm bằng chứng rồi gửi nhân viên duyệt (server
     *                     kiểm ngưỡng lỗi AI); bỏ trống là chấm tự động như thường
     */
    record LivenessUpload(String sessionId, String captureMode, String platform, UploadedFile video,
                          List<UploadedFile> frames, String frameTimesMs, String reviewMode) {

        /** Gửi video chấm tự động như thường (không có chế độ duyệt tay). */
        public LivenessUpload(String sessionId, String captureMode, String platform, UploadedFile video,
                              List<UploadedFile> frames, String frameTimesMs) {
            this(sessionId, captureMode, platform, video, frames, frameTimesMs, null);
        }
    }

    record UploadedFile(String originalFilename, String contentType, byte[] bytes) {
    }
}
