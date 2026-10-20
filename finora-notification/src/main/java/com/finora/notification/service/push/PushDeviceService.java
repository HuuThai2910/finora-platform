package com.finora.notification.service.push;

import com.finora.common.exception.BusinessException;
import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.DevicePlatform;
import com.finora.notification.domain.DeviceRevocationReason;
import com.finora.notification.domain.PushDevice;
import com.finora.notification.domain.PushDeviceStatus;
import com.finora.notification.dto.response.PushDeviceResponse;
import com.finora.notification.repository.PushDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vòng đời thiết bị nhận push (D62): đăng ký/làm mới, gỡ khi đăng xuất, giới hạn số thiết bị.
 * <p>
 * Đăng ký là upsert theo token, chạy được nhiều lần (mỗi lần mở app) mà không tạo bản ghi thừa:
 * <ul>
 *   <li>token đã thuộc chính người này → làm mới, trả cùng {@code deviceId};</li>
 *   <li>token đang thuộc người khác (đổi tài khoản trên cùng máy mà lần đăng xuất trước không gỡ được) → thu hồi dòng cũ
 *       ({@code REASSIGNED}) rồi tạo dòng mới: push của người trước không bao giờ tới máy người sau;</li>
 *   <li>vượt {@code maxDevicesPerUser} → thu hồi thiết bị lâu không đăng ký lại nhất ({@code DEVICE_LIMIT}).</li>
 * </ul>
 * Hai lần đăng ký cùng token đồng thời: dòng được khóa theo token; lần chèn thua unique {@code token} được thử lại một
 * lần trong transaction mới thay vì trả lỗi cho app.
 */
@Slf4j
@Service
public class PushDeviceService {

    private final PushDeviceRepository devices;
    private final TransactionTemplate transactions;
    private final NotificationProperties properties;
    private final Clock clock;

    public PushDeviceService(PushDeviceRepository devices, TransactionTemplate transactions,
            NotificationProperties properties, Clock clock) {
        this.devices = devices;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    public PushDeviceResponse register(String userId, String token, DevicePlatform platform, String appVersion) {
        String normalized = token == null ? null : token.trim();
        if (!PushTokens.isValid(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PUSH_TOKEN_INVALID",
                    "Push token không đúng định dạng Expo");
        }
        DevicePlatform resolvedPlatform = platform == null ? DevicePlatform.UNKNOWN : platform;
        try {
            return transactions.execute(status -> upsert(userId, normalized, resolvedPlatform, appVersion));
        } catch (DataIntegrityViolationException raced) {
            // Lần đăng ký song song cùng token đã chèn trước: lần này đọc lại và làm mới dòng đó.
            return transactions.execute(status -> upsert(userId, normalized, resolvedPlatform, appVersion));
        }
    }

    /** Đăng xuất: chỉ gỡ thiết bị của chính người gọi; ID lạ hoặc của người khác không làm gì (không lộ sự tồn tại). */
    public void unregister(String userId, UUID deviceId) {
        transactions.executeWithoutResult(status -> devices.findByIdForUpdate(deviceId)
                .filter(device -> device.getUserId().equals(userId))
                .filter(device -> device.revoke(DeviceRevocationReason.LOGOUT, clock.instant()))
                .ifPresent(device -> log.info("Gỡ thiết bị nhận push khi đăng xuất: deviceId={}, userId={}, tokenRef={}",
                        device.getId(), userId, device.tokenRef())));
    }

    private PushDeviceResponse upsert(String userId, String token, DevicePlatform platform, String appVersion) {
        Instant now = clock.instant();
        String hash = PushTokens.hash(token);
        Optional<PushDevice> existing = devices.findByTokenForUpdate(token);
        if (existing.isPresent() && existing.get().getUserId().equals(userId)) {
            PushDevice device = existing.get();
            device.refresh(platform, appVersion, now);
            return PushDeviceResponse.from(device);
        }
        existing.ifPresent(previous -> {
            previous.revoke(DeviceRevocationReason.REASSIGNED, now);
            devices.saveAndFlush(previous);
            log.warn("Push token chuyển sang tài khoản khác trên cùng thiết bị: oldDeviceId={}, tokenRef={}",
                    previous.getId(), previous.tokenRef());
        });
        PushDevice device = devices.saveAndFlush(PushDevice.register(userId, token, hash, platform, appVersion, now));
        enforceDeviceLimit(userId, now);
        log.info("Đăng ký thiết bị nhận push: deviceId={}, userId={}, platform={}, tokenRef={}",
                device.getId(), userId, platform, device.tokenRef());
        return PushDeviceResponse.from(device);
    }

    private void enforceDeviceLimit(String userId, Instant now) {
        List<PushDevice> active = devices.findByUserForUpdate(userId, PushDeviceStatus.ACTIVE);
        int limit = properties.push().maxDevicesPerUser();
        for (int index = limit; index < active.size(); index++) {
            PushDevice stale = active.get(index);
            stale.revoke(DeviceRevocationReason.DEVICE_LIMIT, now);
            log.info("Gỡ thiết bị nhận push vì vượt giới hạn: deviceId={}, userId={}, limit={}",
                    stale.getId(), userId, limit);
        }
    }
}
