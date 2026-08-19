package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.AuthService;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.RateLimitService;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Triển khai dịch vụ xác thực — đăng ký, đăng nhập, làm mới token, quên/đặt lại mật khẩu.
 * <p>
 * Phối hợp giữa Keycloak (quản lý credential), PostgreSQL (hồ sơ người dùng),
 * Redis (rate limit/OTP) và Kafka (phát event cho notification).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final RateLimitService rateLimitService;
    private final NotificationService notificationService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Đăng ký ─────────────────────────────────────────────────────

    @Override
    public AuthResponse register(RegisterRequest request) {
        // Kiểm tra email đã tồn tại trong DB chưa
        if (userProfileRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }

        UserRole role = request.getRole() != null ? request.getRole() : UserRole.BORROWER;

        // Tạo user trên Keycloak trước — nếu thất bại thì không có orphan trong DB
        String keycloakUserId = keycloakAdminService.createUser(
                request.getEmail(), request.getPassword(), request.getFullName(), role);

        try {
            // Lưu hồ sơ vào DB
            UserProfile profile = UserProfile.builder()
                    .keycloakUserId(UUID.fromString(keycloakUserId))
                    .email(request.getEmail())
                    .fullName(request.getFullName())
                    .role(role)
                    .build();

            profile = userProfileRepository.save(profile);

            // Gửi email chào mừng qua notification service (best-effort)
            notificationService.sendWelcomeEmail(
                    profile.getId(), profile.getEmail(), profile.getFullName());

            log.info("Đăng ký thành công: userId={}, email={}",
                    profile.getId(), PiiMasker.maskEmail(request.getEmail()));

            return new AuthResponse(
                    profile.getId(),
                    profile.getEmail(),
                    profile.getFullName(),
                    List.of(role.name()),
                    null, null);

        } catch (Exception e) {
            // Rollback Keycloak user nếu lưu DB thất bại — best-effort
            log.error("Lưu DB thất bại sau khi tạo Keycloak user — rollback: email={}",
                    PiiMasker.maskEmail(request.getEmail()), e);
            keycloakAdminService.deleteUser(keycloakUserId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể hoàn tất đăng ký, vui lòng thử lại sau");
        }
    }

    // ── Đăng nhập ───────────────────────────────────────────────────

    @Override
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = request.getEmail();

        // Kiểm tra tài khoản có đang bị khóa tạm không
        if (rateLimitService.isLoginBlocked(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "Tài khoản tạm khóa, vui lòng thử lại sau 15 phút");
        }

        AccessTokenResponse tokenResponse;
        try {
            tokenResponse = keycloakAdminService.getUserToken(email, request.getPassword());
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
                // Sai mật khẩu — ghi nhận thất bại
                int failCount = rateLimitService.recordFailedLogin(email, ipAddress);

                if (failCount >= 5) {
                    // Gửi cảnh báo hoạt động đáng ngờ qua notification service
                    UserProfile profile = userProfileRepository.findByEmail(email).orElse(null);
                    Long userId = profile != null ? profile.getId() : null;
                    notificationService.sendSuspiciousActivityAlert(
                            userId, email, ipAddress, userAgent,
                            "Đăng nhập sai mật khẩu " + failCount + " lần liên tiếp");
                }

                throw new BusinessException(HttpStatus.UNAUTHORIZED,
                        "Email hoặc mật khẩu không đúng");
            }
            throw e;
        }

        // Đăng nhập thành công — xóa bộ đếm thất bại
        rateLimitService.resetFailedLogin(email);

        // Tìm hồ sơ người dùng
        UserProfile profile = userProfileRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Không tìm thấy hồ sơ người dùng"));

        log.info("Đăng nhập thành công: userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(email));

        return new AuthResponse(
                profile.getId(),
                profile.getEmail(),
                profile.getFullName(),
                List.of(profile.getRole().name()),
                tokenResponse.getToken(),
                tokenResponse.getRefreshToken());
    }

    // ── Làm mới token ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(String refreshToken) {
        return keycloakAdminService.refreshToken(refreshToken);
    }

    // ── Đăng xuất ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            keycloakAdminService.revokeRefreshToken(refreshToken);
        }
        log.debug("Đã xử lý yêu cầu đăng xuất");
    }

    // ── Quên mật khẩu ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void forgotPassword(String email, String ipAddress) {
        // Rate limit — tối đa 3 yêu cầu OTP mỗi email mỗi giờ
        if (!rateLimitService.recordOtpRequest(email)) {
            log.info("Vượt rate limit OTP cho email={}", PiiMasker.maskEmail(email));
            return;
        }

        // Tìm hồ sơ — nếu không tồn tại, trả về im lặng (chống dò tài khoản)
        UserProfile profile = userProfileRepository.findByEmail(email).orElse(null);
        if (profile == null) {
            log.info("Yêu cầu OTP cho email không tồn tại: email={}", PiiMasker.maskEmail(email));
            return;
        }

        // Tạo OTP 6 chữ số ngẫu nhiên
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));

        // Lưu OTP vào Redis với TTL
        rateLimitService.storeOtp(profile.getId(), otp);

        // Gửi email chứa mã OTP qua notification service (best-effort)
        notificationService.sendPasswordResetOtp(profile.getId(), email, otp);

        log.info("OTP requested for {}", PiiMasker.maskEmail(email));
    }

    // ── Đặt lại mật khẩu ───────────────────────────────────────────

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        // Tìm hồ sơ theo email
        UserProfile profile = userProfileRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "Mã không hợp lệ hoặc đã hết hạn"));

        // Xác minh OTP
        if (!rateLimitService.verifyOtp(profile.getId(), request.getOtp())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Mã không hợp lệ hoặc đã hết hạn");
        }

        // Đổi mật khẩu trên Keycloak
        keycloakAdminService.resetPassword(
                profile.getKeycloakUserId().toString(), request.getNewPassword());

        log.info("Đã đặt lại mật khẩu cho userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(request.getEmail()));
    }
}
