package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.PendingRegistration;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.dto.request.ResetPasswordRequest;
import com.finora.user.dto.request.VerifyRegistrationRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.dto.response.RegistrationChallengeResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.AuthService;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.PendingRegistrationStore;
import com.finora.user.service.RateLimitService;
import com.finora.user.support.CryptoUtils;
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
 * Redis (rate limit, OTP, đăng ký tạm) và finora-notification (email).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    /** Độ dài mã OTP — client hiển thị đúng số ô nhập theo giá trị này */
    private static final int OTP_LENGTH = 6;

    private static final String MSG_OTP_INVALID = "Mã không hợp lệ hoặc đã hết hạn";
    private static final String MSG_REGISTRATION_EXPIRED =
            "Phiên đăng ký đã hết hạn, vui lòng đăng ký lại";
    private static final String MSG_OTP_RATE_LIMITED =
            "Bạn đã yêu cầu mã quá nhiều lần, vui lòng thử lại sau một giờ";
    private static final String MSG_EMAIL_TAKEN = "Email đã được sử dụng";
    private static final String MSG_PHONE_TAKEN = "Số điện thoại này đã được đăng ký trong hệ thống";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final RateLimitService rateLimitService;
    private final NotificationService notificationService;
    private final PendingRegistrationStore pendingRegistrationStore;
    private final CryptoProperties cryptoProperties;

    // ── Đăng ký bước 1: gửi OTP ─────────────────────────────────────

    /**
     * Chưa tạo tài khoản ở bước này. Thông tin đăng ký (kể cả mật khẩu, đã mã hoá)
     * nằm trong Redis với TTL ngắn, nên người dùng bỏ dở giữa chừng sẽ không để lại
     * tài khoản rác trên Keycloak hay hồ sơ mồ côi trong DB.
     */
    @Override
    @Transactional(readOnly = true)
    public RegistrationChallengeResponse register(RegisterRequest request) {
        String email = request.getEmail();
        String phone = normalizePhone(request.getPhone());

        if (userProfileRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, MSG_EMAIL_TAKEN);
        }

        if (phone != null && userProfileRepository.existsByPhoneHash(hashPhone(phone))) {
            throw new BusinessException(HttpStatus.CONFLICT, MSG_PHONE_TAKEN);
        }

        // Dùng chung hạn mức OTP với quên mật khẩu — tối đa 3 yêu cầu mỗi email mỗi giờ
        if (!rateLimitService.recordOtpRequest(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, MSG_OTP_RATE_LIMITED);
        }

        // Họ tên không thu lúc đăng ký — chuẩn hoá chuỗi rỗng về null để hồ sơ
        // thể hiện đúng "chưa có tên"; tên thật được điền từ OCR CCCD khi eKYC.
        String fullName = request.getFullName() != null && !request.getFullName().isBlank()
                ? request.getFullName().trim()
                : null;

        pendingRegistrationStore.save(new PendingRegistration(
                email,
                request.getPassword(),
                fullName,
                phone,
                request.getRole() != null ? request.getRole() : UserRole.BORROWER));

        sendRegistrationOtp(email);

        log.info("Đã tạo phiên đăng ký chờ xác thực: email={}", PiiMasker.maskEmail(email));

        return buildChallenge(email);
    }

    // ── Đăng ký: gửi lại OTP ────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public RegistrationChallengeResponse resendRegistrationOtp(String email) {
        // Không còn phiên đăng ký tạm nghĩa là chưa đăng ký hoặc đã hết hạn — phải khai lại từ đầu
        if (pendingRegistrationStore.find(email).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, MSG_REGISTRATION_EXPIRED);
        }

        if (!rateLimitService.recordOtpRequest(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, MSG_OTP_RATE_LIMITED);
        }

        sendRegistrationOtp(email);
        return buildChallenge(email);
    }

    // ── Đăng ký bước 2: xác thực OTP và tạo tài khoản ───────────────

    @Override
    public AuthResponse verifyRegistration(VerifyRegistrationRequest request) {
        String email = request.getEmail();

        PendingRegistration pending = pendingRegistrationStore.find(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, MSG_REGISTRATION_EXPIRED));

        if (!rateLimitService.verifyRegistrationOtp(email, request.getOtp())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, MSG_OTP_INVALID);
        }

        // Kiểm tra lại ngay trước khi ghi: email hoặc số điện thoại có thể đã được
        // phiên đăng ký khác chiếm trong lúc mã OTP này còn hiệu lực
        if (userProfileRepository.existsByEmail(email)) {
            pendingRegistrationStore.remove(email);
            throw new BusinessException(HttpStatus.CONFLICT, MSG_EMAIL_TAKEN);
        }

        String phoneHash = pending.phone() != null ? hashPhone(pending.phone()) : null;
        if (phoneHash != null && userProfileRepository.existsByPhoneHash(phoneHash)) {
            pendingRegistrationStore.remove(email);
            throw new BusinessException(HttpStatus.CONFLICT, MSG_PHONE_TAKEN);
        }

        // Tạo user trên Keycloak trước — nếu thất bại thì không có orphan trong DB
        String keycloakUserId = keycloakAdminService.createUser(
                email, pending.password(), pending.fullName(), pending.role());

        UserProfile profile;
        AccessTokenResponse tokenResponse;
        try {
            profile = userProfileRepository.save(UserProfile.builder()
                    .keycloakUserId(UUID.fromString(keycloakUserId))
                    .email(email)
                    .fullName(pending.fullName())
                    .phoneHash(phoneHash)
                    .phoneEncrypted(pending.phone())
                    .role(pending.role())
                    .build());

            // Lấy token trước khi xoá bản ghi tạm vì đó là nơi duy nhất còn mật khẩu người dùng.
            // Nằm chung khối try với lưu DB: nếu cấp token hỏng thì giao dịch rollback hồ sơ,
            // nên user trên Keycloak cũng phải bị xoá, nếu không lần đăng ký lại sẽ vướng 409
            // vĩnh viễn vì DB thì trống mà Keycloak thì đã có email đó.
            tokenResponse = keycloakAdminService.getUserToken(email, pending.password());
        } catch (Exception e) {
            // Rollback Keycloak user — best-effort
            log.error("Không hoàn tất được đăng ký sau khi tạo Keycloak user — rollback: email={}",
                    PiiMasker.maskEmail(email), e);
            keycloakAdminService.deleteUser(keycloakUserId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể hoàn tất đăng ký, vui lòng thử lại sau");
        }

        pendingRegistrationStore.remove(email);
        rateLimitService.clearRegistrationOtp(email);

        notificationService.sendWelcomeEmail(profile.getId(), profile.getEmail(), profile.getFullName());

        log.info("Đăng ký thành công: userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(email));

        return new AuthResponse(
                profile.getId(),
                profile.getEmail(),
                profile.getFullName(),
                List.of(profile.getRole().name()),
                tokenResponse.getToken(),
                tokenResponse.getRefreshToken());
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

        String otp = generateOtp();

        // Lưu OTP vào Redis với TTL
        rateLimitService.storeOtp(profile.getId(), otp);

        // Gửi email chứa mã OTP qua notification service (best-effort)
        notificationService.sendPasswordResetOtp(profile.getId(), email, otp);

        log.info("OTP requested for {}", PiiMasker.maskEmail(email));
    }

    // ── Kiểm tra OTP đặt lại mật khẩu ──────────────────────────────

    /**
     * Trả cùng thông báo lỗi cho "email không tồn tại" và "mã sai" để không lộ
     * email nào đã đăng ký — nhất quán với {@link #forgotPassword} và
     * {@link #resetPassword}. Mã khớp thì vẫn giữ lại trong Redis cho bước chốt,
     * nhưng mỗi lần kiểm tra đều tính một lần thử chống dò mã.
     */
    @Override
    @Transactional(readOnly = true)
    public void verifyResetOtp(String email, String otp) {
        UserProfile profile = userProfileRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, MSG_OTP_INVALID));

        if (!rateLimitService.checkOtp(profile.getId(), otp)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, MSG_OTP_INVALID);
        }
    }

    // ── Đặt lại mật khẩu ───────────────────────────────────────────

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        // Tìm hồ sơ theo email
        UserProfile profile = userProfileRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, MSG_OTP_INVALID));

        // Xác minh OTP
        if (!rateLimitService.verifyOtp(profile.getId(), request.getOtp())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, MSG_OTP_INVALID);
        }

        // Đổi mật khẩu trên Keycloak
        keycloakAdminService.resetPassword(
                profile.getKeycloakUserId().toString(), request.getNewPassword());

        log.info("Đã đặt lại mật khẩu cho userId={}, email={}",
                profile.getId(), PiiMasker.maskEmail(request.getEmail()));
    }

    // ── Tiện ích nội bộ ────────────────────────────────────────────

    /**
     * Sinh và gửi OTP đăng ký. Gửi email là best-effort: notification lỗi thì luồng
     * đăng ký vẫn đứng vững, người dùng bấm gửi lại mã là có mã mới.
     */
    private void sendRegistrationOtp(String email) {
        String otp = generateOtp();
        rateLimitService.storeRegistrationOtp(email, otp);
        notificationService.sendRegistrationOtp(email, otp);
    }

    private RegistrationChallengeResponse buildChallenge(String email) {
        // OTP và phiên đăng ký tạm có cùng TTL; lấy giá trị nhỏ hơn để đồng hồ đếm
        // ngược trên client không dài hơn thời hạn thực tế
        long expiresIn = Math.min(rateLimitService.otpTtlSeconds(), pendingRegistrationStore.ttlSeconds());
        return new RegistrationChallengeResponse(
                email, PiiMasker.maskEmail(email), OTP_LENGTH, expiresIn);
    }

    private String hashPhone(String phone) {
        return CryptoUtils.hmacSha256(phone, cryptoProperties.getHmacSecret());
    }

    private static String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private static String normalizePhone(String phone) {
        return phone == null || phone.isBlank() ? null : phone.trim();
    }
}
