package com.finora.common.environment;

import java.util.Locale;

/**
 * Môi trường chạy của service (D37), đọc từ {@code finora.environment} ({@code FINORA_ENVIRONMENT}), mặc định
 * {@code local}.
 * <p>
 * {@code local}, {@code e2e}, {@code demo} được dùng cấu hình giả lập: provider tiền mock, chữ ký MOCK, khóa PIN dev,
 * đồng hồ/điểm dừng E2E, nút "Giả lập thanh toán thành công". {@code staging} và {@code production} thì không: service
 * từ chối khởi động nếu còn cấu hình nào như vậy, để tiền giả không bao giờ chạy cạnh người dùng thật.
 */
public enum FinoraEnvironment {
    LOCAL, E2E, DEMO, STAGING, PRODUCTION;

    public static final String PROPERTY = "finora.environment";

    public boolean allowsSimulation() {
        return this == LOCAL || this == E2E || this == DEMO;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FinoraEnvironment parse(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("finora.environment không hợp lệ: " + value
                    + " (local, e2e, demo, staging, production)", exception);
        }
    }
}
