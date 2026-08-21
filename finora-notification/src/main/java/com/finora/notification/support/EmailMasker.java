package com.finora.notification.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Tiện ích mask email trong log — hiện 2 ký tự đầu + domain.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmailMasker {

    /**
     * Mask email đơn giản cho log — hiện 2 ký tự đầu + domain.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";
        int visibleChars = Math.min(2, atIndex);
        return email.substring(0, visibleChars) + "***" + email.substring(atIndex);
    }
}
