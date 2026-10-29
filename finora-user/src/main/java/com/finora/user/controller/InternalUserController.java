package com.finora.user.controller;

import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.service.UserIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint nội bộ cho service khác gọi bằng token service account, không đi qua gateway công khai.
 * <p>
 * Quyền {@code user:identity:read_internal} là client role của {@code finora-user-client}, chỉ gán cho service account
 * {@code finora-investment-client}; không nằm trong role người dùng nào nên token người dùng nhận 403.
 */
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserIdentityService userIdentityService;

    /**
     * Họ tên và CCCD đã che (chỉ giữ 4 số cuối) của một người dùng, để Investment in lên giấy xác nhận chuyển
     * nhượng Note. {@code userId} là giá trị Investment lưu làm investorId/buyer_id/seller_id.
     */
    @GetMapping("/{userId}/identity")
    @PreAuthorize("hasAuthority('user:identity:read_internal')")
    public ResponseEntity<UserIdentityResponse> getIdentity(@PathVariable String userId) {
        return ResponseEntity.ok(userIdentityService.identityOf(userId));
    }
}
