package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.user.dto.request.EkycConsentAcceptRequest;
import com.finora.user.dto.response.EkycConsentResponse;
import com.finora.user.service.EkycConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Điều khoản xử lý dữ liệu eKYC (EKYC-002 mục 3.1): xem nội dung hiện hành, đồng ý, rút lại.
 * <p>
 * Nội dung lấy từ server chứ không viết cứng trong app, để đổi điều khoản chỉ cần tăng phiên bản
 * và mọi người dùng phải đồng ý lại.
 */
@RestController
@RequestMapping("/api/v1/users/profile/ekyc/consent")
@RequiredArgsConstructor
public class EkycConsentController {

    private final EkycConsentService consentService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycConsentResponse> getConsent() {
        return BaseResponse.success(consentService.getConsent(SecurityUtils.getCurrentKeycloakUserId()));
    }

    /** Đồng ý đúng phiên bản và nội dung đã đọc; lệch bản hiện hành trả {@code 409 CONSENT_VERSION_STALE}. */
    @PostMapping
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycConsentResponse> acceptConsent(@Valid @RequestBody EkycConsentAcceptRequest request) {
        return BaseResponse.success(consentService.accept(SecurityUtils.getCurrentKeycloakUserId(), request));
    }

    /** Rút lại: xoá ảnh/video và bản nháp, hồ sơ về chưa xác minh (giữ hash số định danh). */
    @DeleteMapping
    @PreAuthorize("hasAuthority('user:cccd:scan')")
    public BaseResponse<EkycConsentResponse> withdrawConsent() {
        return BaseResponse.success(consentService.withdraw(SecurityUtils.getCurrentKeycloakUserId()));
    }
}
