package com.finora.notification.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Escape HTML đơn giản để chống XSS trong nội dung email.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HtmlSanitizer {

    /**
     * Escape các ký tự HTML nguy hiểm.
     */
    public static String escape(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
