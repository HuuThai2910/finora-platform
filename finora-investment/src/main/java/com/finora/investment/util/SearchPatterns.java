package com.finora.investment.util;

import java.util.Locale;

/**
 * Mẫu {@code LIKE '%…%'} cho ô tìm kiếm của các danh sách quản trị. Ký tự đặc biệt của LIKE và ký tự thoát {@code !}
 * được thoát bằng {@code !}; câu JPQL dùng kèm {@code ESCAPE '!'}.
 */
public final class SearchPatterns {

    /** Độ dài tối đa của chuỗi tìm, khớp {@code maxLength} của ô tìm trên web. */
    public static final int MAX_LENGTH = 100;

    private SearchPatterns() {
    }

    /**
     * Mẫu so với cột mã lưu chữ hoa (mã hồ sơ {@code HS-}/{@code LA-}, mã hợp đồng {@code HĐ-}/{@code LC-}, mã lần khớp,
     * số giấy xác nhận): viết hoa và đổi {@code HD-} gõ không dấu thành {@code HĐ-}. {@code null} khi chuỗi tìm rỗng.
     */
    public static String containsCode(String q) {
        String value = trimmed(q);
        return value == null ? null : "%" + escape(value.toUpperCase(Locale.ROOT).replace("HD-", "HĐ-")) + "%";
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
