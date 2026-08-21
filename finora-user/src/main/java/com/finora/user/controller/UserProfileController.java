package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.user.dto.request.EkycVerifyRequest;
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
 * Controller hồ sơ người dùng — xem hồ sơ và xác minh eKYC (CCCD).
 * <p>
 * Không còn API cập nhật/khai CCCD tay: thông tin định danh được điền từ OCR
 * khi quét eKYC. Tất cả endpoint yêu cầu xác thực (JWT từ Keycloak).
 * <p>
 * Endpoint eKYC ({@code ekyc-verify}) trả bao {@link BaseResponse} theo contract
 * mà mobile đã tích hợp; endpoint hồ sơ trả DTO trực tiếp.
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

    // ── eKYC — Xác minh giấy tờ ────────────────────────────────────

    /**
     * Bước quét eKYC: gửi ảnh hai mặt CCCD, nhận về bản nháp thông tin OCR.
     * <p>
     * Hồ sơ CHƯA được lưu ở bước này — bản nháp nằm trên server chờ người dùng
     * soát rồi xác nhận qua {@code ekyc-confirm}; sai thông tin thì quét lại.
     */
    @PostMapping("/profile/ekyc-verify")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycResultResponse> verifyEkyc(
            @Valid @RequestBody EkycVerifyRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        EkycResultResponse result = ekycVerificationService.verify(keycloakUserId, request);
        return BaseResponse.success(result);
    }

    /**
     * Bước xác nhận eKYC: người dùng đồng ý với bản nháp thì hồ sơ mới được lưu
     * và chuyển VERIFIED. Không nhận dữ liệu từ client — bản nháp đọc từ server
     * để người dùng không sửa được thông tin đã OCR.
     */
    @PostMapping("/profile/ekyc-confirm")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycResultResponse> confirmEkyc() {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(ekycVerificationService.confirm(keycloakUserId));
    }
}
