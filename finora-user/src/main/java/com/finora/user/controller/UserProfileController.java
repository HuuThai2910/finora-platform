package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.user.dto.request.CccdDataRequest;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.security.SecurityUtils;
import com.finora.user.service.EkycVerificationService;
import com.finora.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller hồ sơ người dùng — xem/cập nhật thông tin cá nhân và eKYC (CCCD).
 * <p>
 * Tất cả endpoint yêu cầu xác thực (JWT từ Keycloak).
 * Quyền truy cập được kiểm soát qua {@code @PreAuthorize} dựa trên authority từ Keycloak.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final EkycVerificationService ekycVerificationService;

    // ── Hồ sơ cá nhân ──────────────────────────────────────────────

    /**
     * Xem hồ sơ của người dùng hiện tại.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public BaseResponse<UserProfileResponse> getMyProfile() {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(userProfileService.getMyProfile(keycloakUserId));
    }

    /**
     * Cập nhật hồ sơ cá nhân (partial update) — chỉ ghi đè trường khác null.
     */
    @PutMapping("/me")
    @PreAuthorize("hasAuthority('user:profile:write')")
    public BaseResponse<UserProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(userProfileService.updateMyProfile(keycloakUserId, request));
    }

    // ── eKYC — CCCD ─────────────────────────────────────────────────

    /**
     * Tiếp nhận dữ liệu CCCD người dùng tự khai — cập nhật hồ sơ eKYC.
     * <p>
     * Đây là đường duy nhất đưa số CCCD vào hồ sơ. Dữ liệu tự khai chưa được
     * tin cậy: bước {@code ekyc-verify} sẽ OCR ảnh CCCD rồi đối chiếu lại.
     */
    @PutMapping("/profile/cccd-manual")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<UserProfileResponse> submitCccdManual(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(userProfileService.submitCccdData(keycloakUserId, request));
    }

    // ── eKYC — Xác minh khuôn mặt ──────────────────────────────────

    /**
     * Cấp thử thách active liveness cho phiên xác minh sắp tới.
     * <p>
     * Chuỗi hành động do server sinh ngẫu nhiên và chỉ dùng được một lần, nên
     * video quay sẵn không qua được bước xác minh phía sau.
     */
    @PostMapping("/profile/liveness-challenge")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<LivenessChallengeResponse> createLivenessChallenge() {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(ekycVerificationService.createLivenessChallenge(keycloakUserId));
    }

    /**
     * Xác minh eKYC: gửi ảnh CCCD và chuỗi frame quay theo thử thách của phiên.
     * <p>
     * Luồng: OCR ảnh CCCD → đối chiếu số CCCD với hồ sơ → active liveness →
     * so khớp khuôn mặt. Kết quả cập nhật trạng thái eKYC trên hồ sơ người dùng.
     */
    @PostMapping("/profile/ekyc-verify")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycResultResponse> verifyEkyc(
            @Valid @RequestBody EkycVerifyRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        EkycResultResponse result = ekycVerificationService.verify(keycloakUserId, request);
        return BaseResponse.success(result);
    }
}
