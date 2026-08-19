package com.finora.user.controller;

import com.finora.common.exception.BusinessException;
import com.finora.user.dto.request.ForgotPasswordRequest;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RefreshTokenRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.service.AuthService;
import com.finora.user.support.AuthCookieManager;
import com.finora.user.support.HttpRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller xác thực — đăng ký, đăng nhập, làm mới token, quên/đặt lại mật khẩu.
 * <p>
 * Hỗ trợ dual-mode:
 * <ul>
 *   <li>Web client: token trong HttpOnly Cookie (SameSite=Lax)</li>
 *   <li>Mobile client (header {@code X-Client-Type: mobile}): token trong response body</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieManager cookieManager;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        AuthResponse authResponse = authService.login(
                request,
                HttpRequestUtils.extractIpAddress(httpRequest),
                HttpRequestUtils.extractUserAgent(httpRequest));

        if (HttpRequestUtils.isMobileClient(httpRequest)) {
            return ResponseEntity.ok(authResponse);
        }

        // Web — đặt token vào cookie, không trả trong body
        cookieManager.addTokenCookies(httpResponse, authResponse.accessToken(), authResponse.refreshToken());
        return ResponseEntity.ok(new AuthResponse(
                authResponse.userId(), authResponse.email(), authResponse.fullName(),
                authResponse.roles(), null, null));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = HttpRequestUtils.extractRefreshToken(
                body != null ? body.refreshToken() : null, httpRequest);

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }

        AccessTokenResponse tokenResponse = authService.refresh(refreshToken);

        if (HttpRequestUtils.isMobileClient(httpRequest)) {
            return ResponseEntity.ok(new AuthResponse(
                    null, null, null, null,
                    tokenResponse.getToken(), tokenResponse.getRefreshToken()));
        }

        cookieManager.addTokenCookies(httpResponse, tokenResponse.getToken(), tokenResponse.getRefreshToken());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = HttpRequestUtils.extractRefreshToken(
                body != null ? body.refreshToken() : null, httpRequest);

        authService.logout(refreshToken);
        cookieManager.clearTokenCookies(httpResponse);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {

        authService.forgotPassword(request.email(), HttpRequestUtils.extractIpAddress(httpRequest));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
