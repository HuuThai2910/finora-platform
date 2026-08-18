package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.util.PiiMasker;
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
 * Dịch vụ xác thực — đăng ký, đăng nhập, làm mới token, quên/đặt lại mật khẩu.
 * <p>
 * Phối hợp giữa Keycloak (quản lý credential), PostgreSQL (hồ sơ người dùng),
 * Redis (rate limit/OTP) và Kafka (phát event cho notification).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final RateLimitService rateLimitService;
    private final NotificationService notificationService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Đăng ký ─────────────────────────────────────────────────────

    /**
     * Đăng ký tài khoản mới — tạo user trên Keycloak rồi lưu hồ sơ vào DB.
     * <p>
     * Nếu Keycloak thành công nhưng DB thất bại, rollback bằng cách xóa user Keycloak (best-effort).
     * Trả về AuthResponse KHÔNG có token — người dùng phải đăng nhập riêng.
     */
    public AuthResponse register(RegisterRequest request) {
        // Kiểm tra email đã tồn tại trong DB chưa
        if (userProfileRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }

        UserRole role = request.role() != null ? request.role() : UserRole.BORROWER;

        // Tạo user trên Keycloak trước — nếu thất bại thì không có orphan trong DB
        String keycloakUserId = keycloakAdminService.createUser(
                request.email(), request.password(), request.fullName(), role);

        try {
            // Lưu hồ sơ vào DB
            UserProfile profile = UserProfile.builder()
                    .keycloakUserId(UUID.fromString(keycloakUserId))
                    .email(request.email())
                    .fullName(request.fullName())
                    .role(role)
                    .build();

            profile = userProfileRepository.save(profile);

            // Gửi email chào mừng qua notification service (best-effort)
            notificationService.sendWelcomeEmail(
                    profile.getId(), profile.getEmail(), profile.getFullName());

            log.info("Đăng ký thành công: userId={}, email={}",
                    profile.getId(), PiiMasker.maskEmail(request.email()));

            return new AuthResponse(
                    profile.getId(),
                    profile.getEmail(),
                    profile.getFullName(),
                    List.of(role.name()),
                    null, null);

        } catch (Exception e) {
            // Rollback Keycloak user nếu lưu DB thất bại — best-effort
            log.error("Lưu DB thất bại sau khi tạo Keycloak user — rollback: email={}",
                    PiiMasker.maskEmail(request.email()), e);
            keycloakAdminService.deleteUser(keycloakUserId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể hoàn tất đăng ký, vui lòng thử lại sau");
        }
    }

    // ── Đăng nhập ───────────────────────────────────────────────────

    /**
     * Đăng nhập bằng email/password — kiểm tra rate limit, xác thực qua Keycloak, trả token.
     * <p>
     * Nếu sai mật khẩu nhiều lần liên tiếp, khóa tạm tài khoản và phát cảnh báo hoạt động đáng ngờ.
     */
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = request.email();

        // Kiểm tra tài khoản có đang bị khóa tạm không
        if (rateLimitService.isLoginBlocked(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "Tài khoản tạm khóa, vui lòng thử lại sau 15 phút");
        }

        AccessTokenResponse tokenResponse;
        try {
            tokenResponse = keycloakAdminService.getUserToken(email, request.password());
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

    /**
     * Làm mới access token bằng refresh token — ủy quyền cho Keycloak xử lý.
     */
    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(String refreshToken) {
        return keycloakAdminService.refreshToken(refreshToken);
    }

    // ── Đăng xuất ───────────────────────────────────────────────────

    /**
     * Đăng xuất — thu hồi refresh token trên Keycloak (best-effort).
     */
    @Transactional(readOnly = true)
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            keycloakAdminService.revokeRefreshToken(refreshToken);
        }
        log.debug("Đã xử lý yêu cầu đăng xuất");
    }

    // ── Quên mật khẩu ──────────────────────────────────────────────

    /**
     * Yêu cầu OTP đặt lại mật khẩu — gửi mã 6 chữ số qua email.
     * <p>
     * Luôn trả phản hồi trung tính để chống dò tài khoản:
     * <ul>
     *   <li>Email không tồn tại → trả thành công (không tiết lộ)</li>
     *   <li>Vượt rate limit → trả thành công (không tiết lộ)</li>
     * </ul>
     */
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

    /**
     * Đặt lại mật khẩu bằng OTP — xác minh mã, đổi password trên Keycloak.
     * <p>
     * Thông báo lỗi chung chung để không tiết lộ thông tin:
     * "Mã không hợp lệ hoặc đã hết hạn" cho mọi trường hợp thất bại.
     */
    public void resetPassword(ResetPasswordRequest request) {
        // Tìm hồ sơ theo email
        UserProfile profile = userProfileRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "Mã không hợp lệ hoặc đã hết hạn"));

        // Xác minh OTP
        if (!rateLimitService.verifyOtp(profile.getId(), request.otp())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Mã không hợp lệ hoặc đã hết hạn");
        }

        // Đổi mật khẩu trên Keycloak
        keycloakAdminService.resetPassword(
                profile.getKeycloakUserId().toString(), request.newPassword());

        log.info("Đã đặt lại mật khẩu cho userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(request.email()));
    }
}
