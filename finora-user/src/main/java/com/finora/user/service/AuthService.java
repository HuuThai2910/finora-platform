package com.finora.user.service;

import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.response.AuthResponse;
import org.keycloak.representations.AccessTokenResponse;

/**
 * Interface dịch vụ xác thực — đăng ký, đăng nhập, làm mới token, quên/đặt lại mật khẩu.
 */
public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String ipAddress, String userAgent);

    AccessTokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    void forgotPassword(String email, String ipAddress);

    void resetPassword(ResetPasswordRequest request);
}
