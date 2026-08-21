package com.finora.user.controller;

import com.finora.common.exception.BusinessException;
import com.finora.user.dto.request.ForgotPasswordRequest;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RefreshTokenRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResendRegistrationOtpRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.request.VerifyRegistrationRequest;
import com.finora.user.dto.request.VerifyResetOtpRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.dto.response.RegistrationChallengeResponse;
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
 * Đăng ký gồm hai bước: {@code /register} gửi OTP qua email, {@code /verify-registration}
 * xác thực mã rồi mới tạo tài khoản và trả token. Đăng nhập không dùng OTP.
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

        return respondWithTokens(authResponse, HttpStatus.OK, httpRequest, httpResponse);
    }

    /**
     * Bước 1 của đăng ký — chỉ gửi OTP, chưa tạo tài khoản.
     * Trả 202 để client hiểu yêu cầu mới được tiếp nhận chứ chưa hoàn tất.
     */
    @PostMapping("/register")
    public ResponseEntity<RegistrationChallengeResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        return ResponseEntity.accepted().body(authService.register(request));
    }

    @PostMapping("/register/resend-otp")
    public ResponseEntity<RegistrationChallengeResponse> resendRegistrationOtp(
            @Valid @RequestBody ResendRegistrationOtpRequest request) {

        return ResponseEntity.ok(authService.resendRegistrationOtp(request.getEmail()));
    }

    /** Bước 2 của đăng ký — xác thực OTP, tạo tài khoản và đăng nhập luôn. */
    @PostMapping("/verify-registration")
    public ResponseEntity<AuthResponse> verifyRegistration(
            @Valid @RequestBody VerifyRegistrationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        AuthResponse authResponse = authService.verifyRegistration(request);
        return respondWithTokens(authResponse, HttpStatus.CREATED, httpRequest, httpResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = HttpRequestUtils.extractRefreshToken(
                body != null ? body.getRefreshToken() : null, httpRequest);

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
                body != null ? body.getRefreshToken() : null, httpRequest);

        authService.logout(refreshToken);
        cookieManager.clearTokenCookies(httpResponse);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {

        authService.forgotPassword(request.getEmail(), HttpRequestUtils.extractIpAddress(httpRequest));
        return ResponseEntity.ok().build();
    }

    /**
     * Kiểm tra OTP đặt lại mật khẩu trước khi cho nhập mật khẩu mới — mã sai được
     * chặn ngay tại màn nhập mã thay vì tới bước cuối. Không tiêu huỷ mã:
     * {@code /reset-password} vẫn xác thực lại chính mã đó khi chốt.
     */
    @PostMapping("/verify-reset-otp")
    public ResponseEntity<Void> verifyResetOtp(@Valid @RequestBody VerifyResetOtpRequest request) {
        authService.verifyResetOtp(request.getEmail(), request.getOtp());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Trả token theo đúng loại client: mobile nhận trong body, web nhận qua cookie
     * và body được lược bỏ token để trình duyệt không đọc được bằng JavaScript.
     */
    private ResponseEntity<AuthResponse> respondWithTokens(
            AuthResponse authResponse,
            HttpStatus successStatus,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        if (HttpRequestUtils.isMobileClient(httpRequest)) {
            return ResponseEntity.status(successStatus).body(authResponse);
        }

        cookieManager.addTokenCookies(httpResponse, authResponse.getAccessToken(), authResponse.getRefreshToken());
        return ResponseEntity.status(successStatus).body(new AuthResponse(
                authResponse.getUserId(), authResponse.getEmail(), authResponse.getFullName(),
                authResponse.getRoles(), null, null));
    }
}
