package com.finora.user.domain.entity;

import com.finora.user.domain.enums.LoginClientType;
import com.finora.user.domain.model.LoginDeviceContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * Một thiết bị đã đăng nhập vào một tài khoản — ánh xạ bảng {@code user_login_devices}.
 * <p>
 * Lần đăng nhập sau trên cùng thiết bị cập nhật tên/HĐH/IP mới nhất, phiên Keycloak và tăng số lần đăng nhập.
 */
@Entity
@Table(name = "user_login_devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserLoginDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private Long userProfileId;

    @Column(name = "device_id", nullable = false, length = 64, updatable = false)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    @Column(name = "os_name", length = 60)
    private String osName;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 10)
    private LoginClientType clientType;

    @Column(name = "app_version", length = 30)
    private String appVersion;

    @Column(name = "last_ip", length = 45)
    private String lastIp;

    @Column(name = "keycloak_session_id", length = 64)
    private String keycloakSessionId;

    @Column(name = "first_login_at", nullable = false, updatable = false)
    private Instant firstLoginAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    @Column(name = "login_count", nullable = false)
    private int loginCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Lần đầu thiết bị đăng nhập vào tài khoản. */
    public static UserLoginDevice firstLogin(Long userProfileId, LoginDeviceContext context, String sessionId,
            Instant now) {
        UserLoginDevice device = new UserLoginDevice();
        device.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        device.deviceId = context.deviceId();
        device.apply(context, sessionId);
        device.firstLoginAt = now;
        device.lastLoginAt = now;
        device.loginCount = 1;
        device.createdAt = now;
        device.updatedAt = now;
        return device;
    }

    /** Đăng nhập lại trên thiết bị đã biết: giữ lần đầu, cập nhật mọi thứ còn lại. */
    public void loggedInAgain(LoginDeviceContext context, String sessionId, Instant now) {
        apply(context, sessionId);
        lastLoginAt = now;
        loginCount++;
        updatedAt = now;
    }

    private void apply(LoginDeviceContext context, String sessionId) {
        deviceName = context.deviceName();
        osName = context.osName();
        clientType = context.clientType();
        appVersion = context.appVersion();
        lastIp = context.ipAddress();
        keycloakSessionId = sessionId;
    }
}
