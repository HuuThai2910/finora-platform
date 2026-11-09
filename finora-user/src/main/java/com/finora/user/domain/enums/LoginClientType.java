package com.finora.user.domain.enums;

/** Loại client đăng nhập: app di động gửi {@code X-Client-Type: mobile}, còn lại là trình duyệt. */
public enum LoginClientType {
    MOBILE,
    WEB
}
