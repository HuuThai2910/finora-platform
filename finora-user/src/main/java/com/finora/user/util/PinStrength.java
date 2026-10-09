package com.finora.user.util;

import java.util.Set;

/**
 * Loại các mã PIN 6 số mà kẻ dò sẽ thử đầu tiên.
 * <p>
 * Với 5 lần thử trước khi khoá, PIN ngẫu nhiên gần như không dò nổi; rủi ro thật
 * nằm ở PIN dễ đoán như 123456 hay 000000, nên chỉ chặn nhóm này.
 */
public final class PinStrength {

    private static final Set<String> COMMON = Set.of(
            "123123", "112233", "121212", "111222", "123321", "147258", "159753", "520520");

    private PinStrength() {
    }

    public static boolean isWeak(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            return true;
        }
        return allSame(pin) || sequential(pin, 1) || sequential(pin, -1) || COMMON.contains(pin);
    }

    private static boolean allSame(String pin) {
        return pin.chars().distinct().count() == 1;
    }

    private static boolean sequential(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
