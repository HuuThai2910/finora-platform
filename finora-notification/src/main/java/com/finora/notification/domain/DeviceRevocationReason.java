package com.finora.notification.domain;

/** Vì sao một thiết bị thôi nhận push. */
public enum DeviceRevocationReason {
    /** Người dùng đăng xuất trên thiết bị đó. */
    LOGOUT,
    /** Expo/APNs/FCM báo token không còn hợp lệ (gỡ app, tắt quyền, token hết hạn). */
    DEVICE_NOT_REGISTERED,
    /** Cùng token được đăng ký cho tài khoản khác (đổi người dùng trên một máy mà lần đăng xuất trước không gỡ được). */
    REASSIGNED,
    /** Vượt số thiết bị đang hoạt động tối đa của một người dùng; thiết bị lâu không đăng ký lại bị gỡ trước. */
    DEVICE_LIMIT
}
