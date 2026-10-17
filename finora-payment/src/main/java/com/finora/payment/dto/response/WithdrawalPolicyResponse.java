package com.finora.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Chính sách rút tiền hiện hành cho người đang đăng nhập (Q4/D19, policy demo cấu hình được). {@code usedToday} và
 * {@code remainingToday} tính theo ngày nghiệp vụ Việt Nam, không tính lệnh đã thất bại (đã hoàn tiền).
 */
public record WithdrawalPolicyResponse(
        BigDecimal minimumAmount,
        BigDecimal dailyLimit,
        BigDecimal feeAmount,
        LocalDate businessDate,
        BigDecimal usedToday,
        BigDecimal remainingToday,
        BigDecimal availableBalance,
        boolean simulatedProvider,
        List<Bank> banks
) {
    public record Bank(String code, String name) {
    }
}
