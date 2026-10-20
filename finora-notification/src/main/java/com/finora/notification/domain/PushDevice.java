package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Một Expo push token đang (hoặc từng) nhận push cho một người dùng (D62).
 * <p>
 * Vòng đời: app đăng ký sau khi được cấp quyền và đăng ký lại mỗi lần mở phiên (làm mới {@code lastRegisteredAt});
 * thu hồi khi đăng xuất, khi Expo báo {@code DeviceNotRegistered}, khi token được đăng ký cho tài khoản khác trên cùng
 * máy, hoặc khi vượt số thiết bị tối đa. Thu hồi xóa token thô (chỉ giữ hash) vì không còn dùng để gửi.
 * Token không bao giờ được log đầy đủ; log chỉ dùng {@link #tokenRef()}.
 */
@Entity
@Table(name = "notification_push_devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDevice implements Persistable<UUID> {

    @Id private UUID id;
    @Column(name = "user_id", nullable = false, length = 100, updatable = false) private String userId;
    @Column(length = 255) private String token;
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false) private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private DevicePlatform platform;
    @Column(name = "app_version", length = 40) private String appVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private PushDeviceStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 40) private DeviceRevocationReason revokedReason;
    @Column(name = "last_registered_at", nullable = false) private Instant lastRegisteredAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @Transient private boolean isNew;

    public static PushDevice register(String userId, String token, String tokenHash, DevicePlatform platform,
            String appVersion, Instant now) {
        PushDevice value = new PushDevice();
        value.id = UUID.randomUUID();
        value.userId = userId;
        value.token = token;
        value.tokenHash = tokenHash;
        value.platform = platform;
        value.appVersion = appVersion;
        value.status = PushDeviceStatus.ACTIVE;
        value.lastRegisteredAt = now;
        value.createdAt = now;
        value.updatedAt = now;
        value.isNew = true;
        return value;
    }

    /** App đăng ký lại cùng token: chỉ làm mới thông tin, giữ nguyên ID để lần đăng xuất sau vẫn gỡ đúng thiết bị. */
    public void refresh(DevicePlatform platform, String appVersion, Instant now) {
        this.platform = platform;
        this.appVersion = appVersion;
        this.lastRegisteredAt = now;
        this.updatedAt = now;
    }

    /** Thu hồi idempotent; thiết bị đã thu hồi giữ lý do đầu tiên. */
    public boolean revoke(DeviceRevocationReason reason, Instant now) {
        if (status == PushDeviceStatus.REVOKED) return false;
        status = PushDeviceStatus.REVOKED;
        revokedReason = reason;
        revokedAt = now;
        token = null;
        updatedAt = now;
        return true;
    }

    public boolean isActive() {
        return status == PushDeviceStatus.ACTIVE;
    }

    /** 12 ký tự đầu của SHA-256 token: đủ để đối chiếu log/hỗ trợ, không dùng được để gửi push. */
    public String tokenRef() {
        return tokenHash.substring(0, 12);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }
}
