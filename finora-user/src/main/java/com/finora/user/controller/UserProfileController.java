package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.user.dto.request.CccdDataRequest;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.security.SecurityUtils;
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
     * Tiếp nhận dữ liệu CCCD từ chip NFC — cập nhật hồ sơ eKYC.
     */
    @PostMapping("/profile/cccd-nfc")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<UserProfileResponse> submitCccdNfc(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(userProfileService.submitCccdData(keycloakUserId, request));
    }

    /**
     * Tiếp nhận dữ liệu CCCD nhập tay từ form web — cùng logic xử lý với NFC.
     */
    @PutMapping("/profile/cccd-manual")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<UserProfileResponse> submitCccdManual(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(userProfileService.submitCccdData(keycloakUserId, request));
    }
}
