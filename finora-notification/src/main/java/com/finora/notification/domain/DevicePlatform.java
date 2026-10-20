package com.finora.notification.domain;

/** Hệ điều hành của thiết bị đăng ký push; chỉ để vận hành thống kê, việc gửi đều đi qua Expo. */
public enum DevicePlatform {
    IOS,
    ANDROID,
    UNKNOWN
}
