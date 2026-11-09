package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.UserLoginDevice;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.model.LoginDeviceContext;
import com.finora.user.dto.response.LoginDeviceResponse;
import com.finora.user.repository.UserLoginDeviceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.LoginDeviceService;
import com.finora.user.util.LoginDeviceHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Ghi và đọc thiết bị đăng nhập (LOGIN-DEVICE-001).
 * <p>
 * Ghi nhận chạy sau khi Keycloak đã cấp token, trong transaction riêng: mọi lỗi (DB, dữ liệu lạ) chỉ log cảnh báo để
 * không làm hỏng lượt đăng nhập. Hai lượt đăng nhập cùng lúc trên một thiết bị mới có thể cùng insert; lượt thua chạm
 * ràng buộc duy nhất thì đọc lại và cập nhật.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginDeviceServiceImpl implements LoginDeviceService {

    private static final int MAX_SESSION_ID = 64;

    private final UserLoginDeviceRepository userLoginDeviceRepository;
    private final UserProfileRepository userProfileRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void recordLogin(Long userProfileId, LoginDeviceContext context, String accessToken) {
        if (userProfileId == null || context == null) {
            return;
        }
        String sessionId = sessionId(accessToken);
        try {
            try {
                transactionTemplate.executeWithoutResult(status -> upsert(userProfileId, context, sessionId));
            } catch (DataIntegrityViolationException race) {
                // Lượt đăng nhập song song vừa tạo dòng của thiết bị này: lần thử lại sẽ thấy dòng đó và cập nhật.
                transactionTemplate.executeWithoutResult(status -> upsert(userProfileId, context, sessionId));
            }
        } catch (RuntimeException exception) {
            log.warn("Không ghi được thiết bị đăng nhập, bỏ qua: userId={}, clientType={}, exceptionType={}",
                    userProfileId, context.clientType(), exception.getClass().getSimpleName());
        }
    }

    private void upsert(Long userProfileId, LoginDeviceContext context, String sessionId) {
        Instant now = Instant.now(clock);
        userLoginDeviceRepository.findByUserProfileIdAndDeviceId(userProfileId, context.deviceId())
                .ifPresentOrElse(
                        device -> device.loggedInAgain(context, sessionId, now),
                        () -> {
                            userLoginDeviceRepository.saveAndFlush(
                                    UserLoginDevice.firstLogin(userProfileId, context, sessionId, now));
                            log.info("Thiết bị mới đăng nhập: userId={}, clientType={}",
                                    userProfileId, context.clientType());
                        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginDeviceResponse> myDevices(UUID keycloakUserId, String currentDeviceId, String currentSessionId) {
        UserProfile profile = profile(keycloakUserId);
        return userLoginDeviceRepository.findByUserProfileIdOrderByLastLoginAtDescIdDesc(profile.getId()).stream()
                .map(device -> response(device, true, currentDeviceId, currentSessionId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginDeviceResponse> devicesOf(Long userProfileId) {
        if (!userProfileRepository.existsById(userProfileId)) {
            throw new ResourceNotFoundException("Hồ sơ người dùng", "id", userProfileId);
        }
        return userLoginDeviceRepository.findByUserProfileIdOrderByLastLoginAtDescIdDesc(userProfileId).stream()
                .map(device -> response(device, false, null, null))
                .toList();
    }

    @Override
    public void signOut(UUID keycloakUserId, Long deviceRowId, String currentDeviceId, String currentSessionId) {
        UserProfile profile = profile(keycloakUserId);
        // Chỉ tìm trong thiết bị của chính người gọi: mã dòng của người khác trả 404 như không tồn tại.
        UserLoginDevice device = userLoginDeviceRepository.findByIdAndUserProfileId(deviceRowId, profile.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Thiết bị đăng nhập", "id", deviceRowId));
        if (isCurrent(device, currentDeviceId, currentSessionId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CURRENT_DEVICE_SIGN_OUT",
                    "Không đăng xuất thiết bị đang dùng ở đây. Hãy dùng nút Đăng xuất của ứng dụng.");
        }
        if (device.getKeycloakSessionId() != null) {
            // Gọi Keycloak ngoài transaction DB; phiên đã hết hạn thì coi như đã đăng xuất.
            keycloakAdminService.deleteUserSession(keycloakUserId.toString(), device.getKeycloakSessionId());
        }
        transactionTemplate.executeWithoutResult(status -> userLoginDeviceRepository.deleteById(device.getId()));
        log.info("Người dùng đăng xuất một thiết bị: userId={}, deviceRowId={}", profile.getId(), deviceRowId);
    }

    private UserProfile profile(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }

    private static LoginDeviceResponse response(UserLoginDevice device, boolean forOwner, String currentDeviceId,
            String currentSessionId) {
        boolean current = forOwner && isCurrent(device, currentDeviceId, currentSessionId);
        return new LoginDeviceResponse(
                device.getId(),
                device.getDeviceName(),
                device.getOsName(),
                device.getClientType().name(),
                device.getAppVersion(),
                forOwner ? LoginDeviceHeaders.maskIp(device.getLastIp()) : device.getLastIp(),
                device.getFirstLoginAt(),
                device.getLastLoginAt(),
                device.getLoginCount(),
                current,
                forOwner && !current && device.getKeycloakSessionId() != null);
    }

    private static boolean isCurrent(UserLoginDevice device, String currentDeviceId, String currentSessionId) {
        return (currentDeviceId != null && currentDeviceId.equals(device.getDeviceId()))
                || (currentSessionId != null && currentSessionId.equals(device.getKeycloakSessionId()));
    }

    /**
     * Claim {@code sid} (phiên Keycloak) của access token vừa cấp. Không cần kiểm chữ ký: token do chính Keycloak trả
     * cho finora-user trong cùng lượt đăng nhập. Token lạ thì {@code null} (thiết bị vẫn được ghi, chỉ không đăng xuất từ
     * xa được).
     */
    String sessionId(String accessToken) {
        if (accessToken == null) {
            return null;
        }
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String sid = payload.path("sid").asText(null);
            return sid == null || sid.isBlank() || sid.length() > MAX_SESSION_ID ? null : sid;
        } catch (Exception exception) {
            return null;
        }
    }
}
