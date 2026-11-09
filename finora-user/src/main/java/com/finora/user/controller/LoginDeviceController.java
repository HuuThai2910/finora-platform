package com.finora.user.controller;

import com.finora.common.security.SecurityUtils;
import com.finora.user.dto.response.LoginDeviceResponse;
import com.finora.user.service.LoginDeviceService;
import com.finora.user.util.LoginDeviceHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Thiết bị đăng nhập" của chính người dùng (LOGIN-DEVICE-001). Thiết bị hiện tại nhận ra bằng {@code X-Device-Id}
 * (hoặc User-Agent khi thiếu) và claim {@code sid} của token đang dùng.
 */
@RestController
@RequestMapping("/api/v1/users/me/devices")
@RequiredArgsConstructor
public class LoginDeviceController {

    private final LoginDeviceService loginDeviceService;

    /** Thiết bị đã đăng nhập vào tài khoản, gần nhất trước; IP đã che. */
    @GetMapping
    public ResponseEntity<List<LoginDeviceResponse>> myDevices(HttpServletRequest request) {
        return ResponseEntity.ok(loginDeviceService.myDevices(SecurityUtils.getCurrentKeycloakUserId(),
                LoginDeviceHeaders.from(request).deviceId(), currentSessionId()));
    }

    /** Đăng xuất một thiết bị khác: xóa phiên Keycloak của thiết bị đó. Thiết bị đang dùng trả 409. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> signOut(@PathVariable Long id, HttpServletRequest request) {
        loginDeviceService.signOut(SecurityUtils.getCurrentKeycloakUserId(), id,
                LoginDeviceHeaders.from(request).deviceId(), currentSessionId());
        return ResponseEntity.noContent().build();
    }

    /** Claim {@code sid} của token đang dùng; {@code null} khi token không có. */
    private static String currentSessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("sid") : null;
    }
}
