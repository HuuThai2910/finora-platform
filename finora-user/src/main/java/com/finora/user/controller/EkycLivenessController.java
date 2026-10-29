package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.service.EkycLivenessService;
import com.finora.user.service.EkycLivenessService.LivenessUpload;
import com.finora.user.service.EkycLivenessService.UploadedFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Kiểm tra khuôn mặt (EKYC-002 mục 3.4): xin thử thách rồi gửi video dạng multipart.
 * <p>
 * Tham số text đều không bắt buộc ở tầng HTTP: thiếu {@code sessionId} là phiên hết hạn
 * ({@code CHALLENGE_EXPIRED}), thiếu file là {@code 400 INVALID_MEDIA} — service quyết định theo đúng
 * thứ tự hợp đồng thay vì để Spring trả lỗi chung.
 */
@RestController
@RequestMapping("/api/v1/users/profile/ekyc/liveness")
@RequiredArgsConstructor
public class EkycLivenessController {

    private final EkycLivenessService livenessService;

    @PostMapping("/challenge")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<LivenessChallengeResponse> createChallenge() {
        return BaseResponse.success(livenessService.createChallenge(SecurityUtils.getCurrentKeycloakUserId()));
    }

    /**
     * Gửi video ({@code captureMode=VIDEO}, part {@code video}) hoặc chuỗi ảnh ({@code STILLS}, các part
     * {@code frames} + {@code frameTimesMs} JSON). Giới hạn multipart 12 MB/file, 40 MB/request; vượt
     * trả {@code 413 PAYLOAD_TOO_LARGE}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycResultResponse> submitLiveness(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "captureMode", required = false) String captureMode,
            @RequestParam(value = "platform", required = false) String platform,
            @RequestPart(value = "video", required = false) MultipartFile video,
            @RequestPart(value = "frames", required = false) List<MultipartFile> frames,
            @RequestParam(value = "frameTimesMs", required = false) String frameTimesMs) {
        LivenessUpload upload = new LivenessUpload(sessionId, captureMode, platform, toUploadedFile(video),
                frames == null ? List.of() : frames.stream().map(EkycLivenessController::toUploadedFile).toList(),
                frameTimesMs);
        return BaseResponse.success(livenessService.submit(SecurityUtils.getCurrentKeycloakUserId(), upload));
    }

    private static UploadedFile toUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new UploadedFile(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA",
                    "Không đọc được tệp gửi lên, vui lòng quay lại.");
        }
    }
}
