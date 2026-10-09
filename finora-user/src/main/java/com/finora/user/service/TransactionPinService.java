package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.PinTokenService;
import com.finora.user.domain.UserPin;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.PinStatusResponse;
import com.finora.user.dto.response.PinTokenResponse;
import com.finora.user.repository.UserPinRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.util.PinStrength;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mã PIN giao dịch — tạo, kiểm, đổi, đặt lại.
 * <p>
 * Kiểm PIN đúng thì cấp pin-token ngắn hạn; các service nghiệp vụ tự kiểm token
 * này ở endpoint gắn {@code @RequirePin}, không gọi ngược lại đây.
 * <p>
 * Các hàm kiểm PIN dùng {@code noRollbackFor = BusinessException.class}: lần nhập
 * sai vừa phải ném lỗi cho client vừa phải lưu bộ đếm, nếu rollback thì bộ đếm
 * không bao giờ tăng và giới hạn 5 lần mất tác dụng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionPinService {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final UserPinRepository pinRepository;
    private final UserProfileRepository profileRepository;
    private final PinTokenService pinTokenService;
    private final KeycloakAdminService keycloakAdminService;
    private final RateLimitService rateLimitService;
    private final NotificationService notificationService;
    private final Clock clock;

    /** Thông tin máy khách để gửi kèm cảnh báo khi PIN bị khoá. */
    public record ClientInfo(String ipAddress, String userAgent) {
    }

    @Transactional(readOnly = true)
    public PinStatusResponse status(UUID keycloakUserId) {
        return pinRepository.findById(keycloakUserId)
                .map(this::toStatus)
                .orElse(new PinStatusResponse(false, false, null, UserPin.MAX_FAILED_ATTEMPTS));
    }

    @Transactional
    public PinStatusResponse create(UUID keycloakUserId, String pin) {
        requireProfile(keycloakUserId);
        if (pinRepository.existsById(keycloakUserId)) {
            throw alreadySet();
        }
        requireStrong(pin);
        try {
            UserPin saved = pinRepository.saveAndFlush(UserPin.create(keycloakUserId, ENCODER.encode(pin), clock.instant()));
            log.info("Đã tạo mã PIN giao dịch: keycloakUserId={}", keycloakUserId);
            return toStatus(saved);
        } catch (DataIntegrityViolationException e) {
            // Hai request tạo PIN chạy song song: bản thua đụng khoá chính.
            throw alreadySet();
        }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public PinTokenResponse verify(UUID keycloakUserId, String pin, PinScope scope, ClientInfo client) {
        UserPin userPin = pinRepository.findForUpdate(keycloakUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "PIN_NOT_SET",
                        "Bạn chưa tạo mã PIN giao dịch"));
        checkPin(userPin, pin, client);
        PinTokenService.IssuedPinToken issued = pinTokenService.issue(keycloakUserId.toString(), scope);
        return new PinTokenResponse(issued.token(), issued.expiresAt());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public PinStatusResponse change(UUID keycloakUserId, String currentPin, String newPin, ClientInfo client) {
        UserPin userPin = pinRepository.findForUpdate(keycloakUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "PIN_NOT_SET",
                        "Bạn chưa tạo mã PIN giao dịch"));
        checkPin(userPin, currentPin, client);
        requireStrong(newPin);
        if (currentPin.equals(newPin)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PIN_UNCHANGED",
                    "Mã PIN mới phải khác mã PIN hiện tại");
        }
        userPin.replaceHash(ENCODER.encode(newPin), clock.instant());
        log.info("Đã đổi mã PIN giao dịch: keycloakUserId={}", keycloakUserId);
        return toStatus(userPin);
    }

    /**
     * Quên PIN: xác minh lại bằng mật khẩu tài khoản. Sai mật khẩu tính chung bộ
     * đếm với đăng nhập, để màn quên PIN không thành đường dò mật khẩu thứ hai.
     */
    @Transactional
    public PinStatusResponse reset(UUID keycloakUserId, String password, String newPin, ClientInfo client) {
        UserProfile profile = requireProfile(keycloakUserId);
        String email = profile.getEmail();
        if (rateLimitService.isLoginBlocked(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_ATTEMPTS_EXCEEDED",
                    "Tài khoản tạm khóa do nhập sai mật khẩu nhiều lần, vui lòng thử lại sau 15 phút");
        }
        requireStrong(newPin);
        confirmPassword(profile, password, client);

        Instant now = clock.instant();
        String hash = ENCODER.encode(newPin);
        UserPin userPin = pinRepository.findForUpdate(keycloakUserId)
                .map(existing -> {
                    existing.replaceHash(hash, now);
                    return existing;
                })
                .orElseGet(() -> pinRepository.save(UserPin.create(keycloakUserId, hash, now)));
        log.info("Đã đặt lại mã PIN giao dịch: keycloakUserId={}", keycloakUserId);
        return toStatus(userPin);
    }

    private void confirmPassword(UserProfile profile, String password, ClientInfo client) {
        String email = profile.getEmail();
        AccessTokenResponse session;
        try {
            session = keycloakAdminService.getUserToken(email, password);
        } catch (BusinessException e) {
            if (e.getStatus() != HttpStatus.UNAUTHORIZED) {
                throw e;
            }
            int failCount = rateLimitService.recordFailedLogin(email, client.ipAddress());
            if (failCount >= 5) {
                notificationService.sendSuspiciousActivityAlert(profile.getId(), email,
                        client.ipAddress(), client.userAgent(),
                        "Nhập sai mật khẩu " + failCount + " lần liên tiếp khi đặt lại mã PIN");
            }
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "PASSWORD_INCORRECT",
                    "Mật khẩu không đúng");
        }
        rateLimitService.resetFailedLogin(email);
        // Phiên Keycloak sinh ra chỉ để kiểm mật khẩu — thu hồi ngay, không để treo.
        try {
            keycloakAdminService.revokeRefreshToken(session.getRefreshToken());
        } catch (RuntimeException e) {
            log.warn("Không thu hồi được phiên kiểm mật khẩu: exceptionType={}", e.getClass().getName());
        }
    }

    private void checkPin(UserPin userPin, String pin, ClientInfo client) {
        Instant now = clock.instant();
        if (userPin.isLocked(now)) {
            throw locked(userPin, now);
        }
        if (ENCODER.matches(pin, userPin.getPinHash())) {
            userPin.recordSuccess(now);
            return;
        }
        boolean lockedNow = userPin.recordFailure(now);
        if (!lockedNow) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PIN_INCORRECT",
                    "Mã PIN không đúng, bạn còn " + userPin.remainingAttempts(now) + " lần thử");
        }
        log.warn("Mã PIN bị khoá do nhập sai nhiều lần: keycloakUserId={}", userPin.getKeycloakUserId());
        profileRepository.findByKeycloakUserId(userPin.getKeycloakUserId()).ifPresent(profile ->
                notificationService.sendSuspiciousActivityAlert(profile.getId(), profile.getEmail(),
                        client.ipAddress(), client.userAgent(),
                        "Nhập sai mã PIN giao dịch " + UserPin.MAX_FAILED_ATTEMPTS + " lần liên tiếp"));
        throw locked(userPin, now);
    }

    private UserProfile requireProfile(UUID keycloakUserId) {
        return profileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND",
                        "Không tìm thấy hồ sơ người dùng"));
    }

    private static void requireStrong(String pin) {
        if (PinStrength.isWeak(pin)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PIN_TOO_WEAK",
                    "Mã PIN quá dễ đoán, tránh dãy số liên tiếp hoặc lặp lại");
        }
    }

    private static BusinessException alreadySet() {
        return new BusinessException(HttpStatus.CONFLICT, "PIN_ALREADY_SET",
                "Bạn đã có mã PIN, hãy dùng chức năng đổi mã PIN");
    }

    private static BusinessException locked(UserPin userPin, Instant now) {
        long minutes = Math.max(1, (Duration.between(now, userPin.getLockedUntil()).toSeconds() + 59) / 60);
        return new BusinessException(HttpStatus.LOCKED, "PIN_LOCKED",
                "Mã PIN tạm khoá do nhập sai nhiều lần. Thử lại sau " + minutes
                        + " phút hoặc chọn Quên mã PIN");
    }

    private PinStatusResponse toStatus(UserPin userPin) {
        Instant now = clock.instant();
        boolean locked = userPin.isLocked(now);
        return new PinStatusResponse(true, locked, locked ? userPin.getLockedUntil() : null,
                userPin.remainingAttempts(now));
    }
}
