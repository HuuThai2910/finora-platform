package com.finora.user.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.AuthCookieProperties;
import com.finora.user.dto.request.*;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Arrays;

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
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    /** Thời gian sống access token cookie — 5 phút */
    private static final long ACCESS_TOKEN_MAX_AGE_SECONDS = 300;

    /** Thời gian sống refresh token cookie — 7 ngày */
    private static final long REFRESH_TOKEN_MAX_AGE_SECONDS = 604_800;

    private static final String COOKIE_ACCESS_TOKEN = "access_token";
    private static final String COOKIE_REFRESH_TOKEN = "refresh_token";
    private static final String HEADER_CLIENT_TYPE = "X-Client-Type";
    private static final String CLIENT_TYPE_MOBILE = "mobile";

    private final AuthService authService;
    private final AuthCookieProperties cookieProperties;

    // ── Đăng nhập ───────────────────────────────────────────────────

    /**
     * Đăng nhập bằng email/password.
     * <p>
     * Web client nhận token qua HttpOnly Cookie; mobile client nhận trong response body.
     */
    @PostMapping("/login")
    public BaseResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = extractIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);

        AuthResponse authResponse = authService.login(request, ipAddress, userAgent);

        if (isMobileClient(httpRequest)) {
            // Mobile — trả token trong body
            return BaseResponse.success(authResponse);
        }

        // Web — đặt token vào cookie, không trả trong body
        addTokenCookies(httpResponse, authResponse.accessToken(), authResponse.refreshToken());

        AuthResponse webResponse = new AuthResponse(
                authResponse.userId(),
                authResponse.email(),
                authResponse.fullName(),
                authResponse.roles(),
                null, null);

        return BaseResponse.success(webResponse);
    }

    // ── Đăng ký ─────────────────────────────────────────────────────

    /**
     * Đăng ký tài khoản mới — trả về thông tin người dùng (không có token, phải login riêng).
     */
    @PostMapping("/register")
    public BaseResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        AuthResponse authResponse = authService.register(request);
        return BaseResponse.created(authResponse);
    }

    // ── Làm mới token ───────────────────────────────────────────────

    /**
     * Làm mới access token.
     * <p>
     * Mobile client gửi refresh token trong body; web client gửi qua cookie.
     */
    @PostMapping("/refresh")
    public BaseResponse<AuthResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = extractRefreshToken(body, httpRequest);

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }

        AccessTokenResponse tokenResponse = authService.refresh(refreshToken);

        if (isMobileClient(httpRequest)) {
            // Mobile — trả token mới trong body
            AuthResponse mobileResponse = new AuthResponse(
                    null, null, null, null,
                    tokenResponse.getToken(),
                    tokenResponse.getRefreshToken());
            return BaseResponse.success(mobileResponse);
        }

        // Web — cập nhật cookie
        addTokenCookies(httpResponse, tokenResponse.getToken(), tokenResponse.getRefreshToken());

        return BaseResponse.success("Làm mới token thành công", null);
    }

    // ── Đăng xuất ───────────────────────────────────────────────────

    /**
     * Đăng xuất — thu hồi refresh token và xóa cookie.
     */
    @PostMapping("/logout")
    public BaseResponse<String> logout(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = extractRefreshToken(body, httpRequest);

        authService.logout(refreshToken);

        // Xóa cookie bất kể client type
        clearTokenCookies(httpResponse);

        return BaseResponse.success("Đăng xuất thành công");
    }

    // ── Quên mật khẩu ──────────────────────────────────────────────

    /**
     * Yêu cầu gửi OTP đặt lại mật khẩu — luôn trả phản hồi trung tính
     * để chống dò tài khoản.
     */
    @PostMapping("/forgot-password")
    public BaseResponse<String> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = extractIpAddress(httpRequest);
        authService.forgotPassword(request.email(), ipAddress);

        return BaseResponse.success("Nếu email tồn tại, mã OTP đã được gửi");
    }

    // ── Đặt lại mật khẩu ───────────────────────────────────────────

    /**
     * Đặt lại mật khẩu bằng mã OTP đã nhận qua email.
     */
    @PostMapping("/reset-password")
    public BaseResponse<String> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        authService.resetPassword(request);
        return BaseResponse.success("Đổi mật khẩu thành công");
    }

    // ── Cookie helpers ──────────────────────────────────────────────

    /**
     * Đặt access_token và refresh_token vào HttpOnly Cookie.
     * SameSite=Lax để chống CSRF; Secure lấy từ cấu hình (false ở local).
     */
    private void addTokenCookies(HttpServletResponse response,
                                  String accessToken, String refreshToken) {
        ResponseCookie accessCookie = ResponseCookie.from(COOKIE_ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/")
                .maxAge(Duration.ofSeconds(ACCESS_TOKEN_MAX_AGE_SECONDS))
                .sameSite("Lax")
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from(COOKIE_REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(REFRESH_TOKEN_MAX_AGE_SECONDS))
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * Xóa cookie token — đặt Max-Age=0 để trình duyệt xóa ngay.
     */
    private void clearTokenCookies(HttpServletResponse response) {
        ResponseCookie clearAccess = ResponseCookie.from(COOKIE_ACCESS_TOKEN, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();

        ResponseCookie clearRefresh = ResponseCookie.from(COOKIE_REFRESH_TOKEN, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/api/v1/auth")
                .maxAge(0)
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, clearAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());
    }

    // ── Request helpers ─────────────────────────────────────────────

    /**
     * Kiểm tra client type từ header {@code X-Client-Type}.
     */
    private boolean isMobileClient(HttpServletRequest request) {
        return CLIENT_TYPE_MOBILE.equalsIgnoreCase(request.getHeader(HEADER_CLIENT_TYPE));
    }

    /**
     * Trích xuất refresh token: ưu tiên body (mobile) → cookie (web).
     */
    private String extractRefreshToken(RefreshTokenRequest body, HttpServletRequest request) {
        // Ưu tiên body (mobile client)
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body.refreshToken();
        }

        // Fallback: cookie (web client)
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(c -> COOKIE_REFRESH_TOKEN.equals(c.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    /**
     * Trích xuất IP address — hỗ trợ proxy/load balancer qua header X-Forwarded-For.
     */
    private String extractIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Lấy IP đầu tiên trong chuỗi (client thực)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
