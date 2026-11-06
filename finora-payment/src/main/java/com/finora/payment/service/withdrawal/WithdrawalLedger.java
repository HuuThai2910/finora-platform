package com.finora.payment.service.withdrawal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bút toán của lệnh rút (D41). Dự trữ: ghi nợ ví {@code AVAILABLE} tổng tiền, ghi có clearing của provider payout (và
 * clearing phí nếu có). Hoàn: bút toán ngược đúng các dòng đó. Khóa idempotency theo mã lệnh nên gọi lại không ghi hai
 * lần. Không dùng {@code HELD}: bucket này được đối chiếu với khoản giữ góp vốn của Investment.
 */
public interface WithdrawalLedger {

    /** Bút toán dự trữ số tiền rút cộng phí khỏi ví; trả mã ledger transaction. */
    UUID reserve(UUID withdrawalId, UUID walletId, BigDecimal amount, BigDecimal fee, String currency);

    /** Bút toán hoàn số tiền rút cộng phí về ví khi lệnh thất bại; trả mã ledger transaction. */
    UUID refund(UUID withdrawalId, UUID walletId, BigDecimal amount, BigDecimal fee, String currency);
}
