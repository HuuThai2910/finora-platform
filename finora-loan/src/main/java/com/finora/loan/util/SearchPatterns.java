package com.finora.loan.util;

import java.util.Locale;

/**
 * Mẫu {@code LIKE '%…%'} cho ô tìm kiếm của các danh sách quản trị. Ký tự đặc biệt của LIKE ({@code %}, {@code _}) và ký
 * tự thoát {@code !} được thoát bằng {@code !}; câu JPQL dùng kèm {@code ESCAPE '!'}.
 */
public final class SearchPatterns {

    /** Độ dài tối đa của chuỗi tìm, khớp {@code maxLength} của ô tìm trên web. */
    public static final int MAX_LENGTH = 100;

    private SearchPatterns() {
    }

    /** Mẫu so với {@code LOWER(cột)} (tên, mô tả); {@code null} khi chuỗi tìm rỗng. */
    public static String containsLower(String q) {
        String value = trimmed(q);
        return value == null ? null : "%" + escape(value.toLowerCase(Locale.ROOT)) + "%";
    }

    /**
     * Mẫu so với cột mã lưu chữ hoa ({@code HS-…}, {@code HĐ-…}, {@code LA-…}, {@code LC-…}, {@code LN-…}): viết hoa và đổi
     * {@code HD-} gõ không dấu thành {@code HĐ-}, nên không cần {@code UPPER()} ở phía database. {@code null} khi rỗng.
     */
    public static String containsCode(String q) {
        String value = trimmed(q);
        return value == null ? null
                : "%" + escape(value.toUpperCase(Locale.ROOT).replace("HD-", DisplayCodes.CONTRACT_PREFIX + "-")) + "%";
    }

    /** Chuỗi tìm đã bỏ khoảng trắng hai đầu và cắt ở {@link #MAX_LENGTH}; {@code null} khi rỗng. */
    public static String trimmed(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String value = q.trim();
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    private static String escape(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
