package com.finora.user.controller;

import com.finora.user.dto.request.CccdDataRequest;
import com.finora.user.dto.request.UpdateProfileRequest;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.security.SecurityUtils;
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
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

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

    @PostMapping("/profile/cccd-nfc")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public ResponseEntity<UserProfileResponse> submitCccdNfc(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.submitCccdData(keycloakUserId, request));
    }

    @PutMapping("/profile/cccd-manual")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public ResponseEntity<UserProfileResponse> submitCccdManual(
            @Valid @RequestBody CccdDataRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.submitCccdData(keycloakUserId, request));
    }
}
