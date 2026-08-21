package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.user.dto.request.CccdDataRequest;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.security.SecurityUtils;
import com.finora.user.service.EkycVerificationService;
import com.finora.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller hồ sơ người dùng — xem/cập nhật thông tin cá nhân và eKYC (CCCD).
 * <p>
 * Tất cả endpoint yêu cầu xác thực (JWT từ Keycloak).
 * <p>
 * Endpoint eKYC ({@code ekyc-verify}) trả bao {@link BaseResponse} theo contract
 * mà mobile đã tích hợp; các endpoint hồ sơ trả DTO trực tiếp.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final EkycVerificationService ekycVerificationService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public ResponseEntity<UserProfileResponse> getMyProfile() {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.getMyProfile(keycloakUserId));
    }

    @PutMapping("/me")
    @PreAuthorize("hasAuthority('user:profile:write')")
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.updateMyProfile(keycloakUserId, request));
    }

    /**
     * Tiếp nhận dữ liệu CCCD người dùng tự khai — cập nhật hồ sơ eKYC.
     * <p>
     * Đây là đường duy nhất đưa số CCCD vào hồ sơ (NFC đã bị loại bỏ). Dữ liệu
     * tự khai chưa được tin cậy: bước {@code ekyc-verify} sẽ OCR ảnh CCCD rồi
     * đối chiếu lại.
     */
    @PutMapping("/profile/cccd-manual")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public ResponseEntity<UserProfileResponse> submitCccdManual(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.submitCccdData(keycloakUserId, request));
    }

    // ── eKYC — Xác minh giấy tờ ────────────────────────────────────

    /**
     * Xác minh eKYC: gửi ảnh hai mặt CCCD.
     * <p>
     * Luồng: OCR ảnh mặt trước → đối chiếu (hoặc điền) số CCCD của hồ sơ; ảnh
     * mặt sau nộp kèm làm bằng chứng. Kết quả cập nhật trạng thái eKYC trên hồ sơ.
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
