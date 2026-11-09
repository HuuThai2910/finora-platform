package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.user.dto.request.ChangePasswordRequest;
import com.finora.user.dto.request.EkycConfirmRequest;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.KycSummaryResponse;
import com.finora.user.dto.response.MyEkycStatusResponse;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.service.AuthService;
import com.finora.user.service.EkycVerificationService;
import com.finora.user.service.KycSummaryService;
import com.finora.user.service.UserProfileService;
import com.finora.user.util.HttpRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller hồ sơ người dùng — xem hồ sơ và xác minh eKYC (CCCD).
 * <p>
 * Không còn API cập nhật/khai CCCD tay: thông tin định danh được điền từ thẻ
 * khi xác minh eKYC. Tất cả endpoint yêu cầu xác thực (JWT từ Keycloak).
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
    private final KycSummaryService kycSummaryService;
    private final AuthService authService;

    /**
     * Hồ sơ của chính mình. Trường {@code permissions} là quyền {@code ops:*} trong JWT đang dùng (ADMIN nhận đủ
     * danh mục) để web quản trị dựng menu; {@code staffRole} là vai trò nhân viên hoặc {@code null}.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public ResponseEntity<UserProfileResponse> getMyProfile() {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return ResponseEntity.ok(userProfileService.getMyProfile(keycloakUserId, SecurityUtils.getCurrentRoles()));
    }

    /**
     * Tự đổi mật khẩu: body {@code {"currentPassword":"...","newPassword":"..."}}. Dùng cho màn đổi mật khẩu bắt buộc
     * của nhân viên mới (mật khẩu tạm) và đổi mật khẩu thông thường; xong thì {@code mustChangePassword} về
     * {@code false}. Mật khẩu hiện tại sai trả {@code 400 CURRENT_PASSWORD_INVALID}. Phiên đang dùng giữ nguyên.
     */
    @PostMapping("/me/change-password")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordRequest request,
                                                 HttpServletRequest httpRequest) {
        authService.changePassword(SecurityUtils.getCurrentKeycloakUserId(), request,
                HttpRequestUtils.extractIpAddress(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * Tóm tắt eKYC của chính người dùng (W2-KYC). Loan gọi bằng token của người vay lúc nộp hồ sơ để chặn người chưa
     * xác minh danh tính; chỉ có trạng thái và tuổi, không có dữ liệu định danh.
     */
    @GetMapping("/me/kyc-summary")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public ResponseEntity<KycSummaryResponse> getMyKycSummary() {
        return ResponseEntity.ok(kycSummaryService.summaryOf(SecurityUtils.getCurrentKeycloakUserId()));
    }

    /**
     * Kết quả lượt xác minh danh tính gần nhất của chính mình, kèm lý do khi nhân viên từ chối — app hiện để khách
     * biết cần chụp lại hay bổ sung gì.
     */
    @GetMapping("/me/ekyc-status")
    @PreAuthorize("hasAuthority('user:profile:read')")
    public ResponseEntity<MyEkycStatusResponse> getMyEkycStatus() {
        return ResponseEntity.ok(kycSummaryService.myEkycStatus(SecurityUtils.getCurrentKeycloakUserId()));
    }

    // ── eKYC — quét thẻ và xác nhận (EKYC-002 mục 3.2, 3.5) ─────────

    /**
     * Bước quét thẻ: gửi ảnh hai mặt thẻ (kèm QR nếu app đã đọc được), nhận bản nháp có nhãn nguồn
     * từng trường, hoặc yêu cầu chụp lại/từ chối.
     * <p>
     * Hồ sơ CHƯA được lưu ở bước này — bản nháp nằm trên server chờ bước kiểm tra khuôn mặt và xác nhận.
     */
    @PostMapping("/profile/ekyc-verify")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    // Mỗi lần quét gọi Gemini có tính phí (D40): 5 lần/10 phút mỗi người dùng.
    @RateLimited(value = "ekyc.ocr", limit = 5, window = "PT10M")
    public BaseResponse<EkycResultResponse> verifyEkyc(
            @Valid @RequestBody EkycVerifyRequest request) {

        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        EkycResultResponse result = ekycVerificationService.verify(keycloakUserId, request);
        return BaseResponse.success(result);
    }

    /**
     * Bước xác nhận: cần đã qua kiểm tra khuôn mặt. Body tuỳ chọn {@code {"edits": {...}}} chỉ sửa được
     * trường {@code editable}; dữ liệu còn lại đọc từ bản nháp phía server để client không sửa được
     * thông tin đã đọc từ thẻ.
     */
    @PostMapping("/profile/ekyc-confirm")
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycResultResponse> confirmEkyc(
            @Valid @RequestBody(required = false) EkycConfirmRequest request) {
        UUID keycloakUserId = SecurityUtils.getCurrentKeycloakUserId();
        return BaseResponse.success(ekycVerificationService.confirm(
                keycloakUserId, request == null ? null : request.edits()));
    }
}
