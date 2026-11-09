package com.finora.user.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sinh mật khẩu tạm cho tài khoản nhân viên do quản trị viên tạo.
 * <p>
 * 14 ký tự từ {@link SecureRandom}, luôn có chữ hoa, chữ thường, chữ số và ký tự đặc biệt — thoả quy tắc 8–64 ký tự
 * của đăng ký và đủ mạnh dù chỉ dùng một lần. Bỏ các ký tự dễ đọc nhầm khi chép tay ({@code 0 O o 1 l I}) và ký tự
 * dễ vỡ khi dán vào chat/email ({@code \ " ' ` < > &} và khoảng trắng).
 */
public final class TemporaryPasswordGenerator {

    /** Độ dài mật khẩu tạm. */
    public static final int LENGTH = 14;

    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    static final String DIGITS = "23456789";
    static final String SPECIAL = "!@#$%*-_+=?";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
    }

    public static String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        // Mỗi nhóm ít nhất một ký tự, phần còn lại lấy ngẫu nhiên từ toàn bộ bảng rồi xáo trộn vị trí.
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIAL));
        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }
        Collections.shuffle(chars, SECURE_RANDOM);
        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }
}
