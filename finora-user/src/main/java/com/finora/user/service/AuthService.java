package com.finora.user.service;

import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.request.VerifyRegistrationRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.dto.response.RegistrationChallengeResponse;
import org.keycloak.representations.AccessTokenResponse;

/**
 * Interface dịch vụ xác thực — đăng ký, đăng nhập, làm mới token, quên/đặt lại mật khẩu.
 * <p>
 * Đăng ký gồm hai bước: {@link #register} gửi OTP, {@link #verifyRegistration} mới tạo tài khoản.
 */
public interface AuthService {

    /** Bước 1 — giữ thông tin đăng ký tạm và gửi OTP qua email. Chưa tạo tài khoản. */
    RegistrationChallengeResponse register(RegisterRequest request);

    /** Gửi lại OTP đăng ký cho phiên đăng ký tạm còn hiệu lực. */
    RegistrationChallengeResponse resendRegistrationOtp(String email);

    /** Bước 2 — xác thực OTP, tạo tài khoản và đăng nhập luôn. */
    AuthResponse verifyRegistration(VerifyRegistrationRequest request);

    AuthResponse login(LoginRequest request, String ipAddress, String userAgent);

    AccessTokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    void forgotPassword(String email, String ipAddress);

    /**
     * Kiểm tra OTP đặt lại mật khẩu trước khi cho người dùng nhập mật khẩu mới.
     * Không tiêu huỷ mã: {@link #resetPassword} vẫn xác thực lại và mới là bước chốt.
     */
    void verifyResetOtp(String email, String otp);

    void resetPassword(ResetPasswordRequest request);
}
