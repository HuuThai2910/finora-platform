package com.finora.investment.client;

import java.math.BigDecimal;

/**
 * Khoản giữ tiền Payment đang có cho một mã lệnh (R3), đọc bằng {@code GET /transactions/holds?orderReference=}.
 *
 * @param status {@code HELD}, {@code CAPTURE_PENDING}, {@code CAPTURED} hoặc {@code RELEASED}
 */
public record PaymentHoldView(String holdReference, BigDecimal amount, String status) {

    public boolean held() {
        return "HELD".equals(status);
    }

    public boolean released() {
        return "RELEASED".equals(status);
    }
}
