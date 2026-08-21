package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình cookie access_token — phục vụ chế độ dual-mode (web dùng cookie, mobile dùng header).
 * Ở local mặc định domain=localhost, secure=false; production cần đổi sang domain thật + secure=true.
 */
@ConfigurationProperties(prefix = "finora.auth.cookie")
@Getter
@Setter
public class AuthCookieProperties {

    private String domain = "localhost";
    private boolean secure;
}
