package com.finora.user.util;

/**
 * Tiện ích che dấu PII trong log — đảm bảo không lộ thông tin cá nhân khi ghi log debug/audit.
 * Mỗi loại dữ liệu có quy tắc mask riêng: giữ đủ ký tự để nhận diện nhưng không lộ toàn bộ.
 */
public final class PiiMasker {

    private static final String MASKED_FALLBACK = "***";

    private PiiMasker() {
        // Lớp tiện ích — không cho phép khởi tạo instance
    }

    /**
     * Mask email: hiện 2 ký tự đầu + domain.
     * "thathieu282@gmail.com" → "th***@gmail.com"
     */
    public static String maskEmail(String email) {
        if (isBlank(email)) return MASKED_FALLBACK;

        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return MASKED_FALLBACK;

        int visibleChars = Math.min(2, atIndex);
        return email.substring(0, visibleChars) + "***" + email.substring(atIndex);
    }

    /**
     * Mask số CCCD/CMND: hiện 3 ký tự đầu + 2 ký tự cuối.
     * "079203012345" → "079*******45"
     */
    public static String maskIdNumber(String idNumber) {
        if (isBlank(idNumber)) return MASKED_FALLBACK;

        int len = idNumber.length();
        if (len <= 5) return "*".repeat(len);

        return idNumber.substring(0, 3)
                + "*".repeat(len - 5)
                + idNumber.substring(len - 2);
    }

    /**
     * Mask số điện thoại: hiện 2 ký tự đầu + 4 ký tự cuối.
     * "0912345678" → "09****5678"
     */
    public static String maskPhone(String phone) {
        if (isBlank(phone)) return MASKED_FALLBACK;

        int len = phone.length();
        if (len <= 6) return "*".repeat(len);

        return phone.substring(0, 2)
                + "*".repeat(len - 6)
                + phone.substring(len - 4);
    }

    /**
     * Mask OTP: che toàn bộ ký tự.
     * "123456" → "******"
     */
    public static String maskOtp(String otp) {
        if (isBlank(otp)) return MASKED_FALLBACK;
        return "*".repeat(otp.length());
    }

    /**
     * Mask họ tên: hiện 1-2 ký tự đầu mỗi từ, phần còn lại thay bằng *.
     * "NGUYỄN VĂN MINH" → "NG*** V** M***"
     */
    public static String maskFullName(String fullName) {
        if (isBlank(fullName)) return MASKED_FALLBACK;

        String[] words = fullName.trim().split("\\s+");
        StringBuilder masked = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) masked.append(' ');
            String word = words[i];
            if (word.length() <= 1) {
                masked.append('*');
            } else {
                // Hiện tối đa 2 ký tự đầu, còn lại mask bằng *
                int visibleChars = Math.min(2, word.length() - 1);
                masked.append(word, 0, visibleChars);
                masked.append("*".repeat(word.length() - visibleChars));
            }
        }

        return masked.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
