package com.finora.payment.integration.payout;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Provider chi tiền ra tài khoản ngân hàng (R4). Provider phải idempotent theo {@code withdrawalId}: gửi lại cùng mã
 * không tạo lệnh chi thứ hai (D42). Kết quả cuối báo bằng callback có chữ ký; {@link #lookup} dùng khi callback không tới.
 */
public interface PayoutProvider {

    String name();

    /**
     * Gửi lệnh chi. Trả kết quả khi provider trả lời rõ ràng (nhận hoặc từ chối); lỗi kết nối/timeout ném exception vì
     * không biết lệnh đã tới provider hay chưa.
     */
    Submission submit(PayoutCommand command);

    /** Tra cứu theo mã lệnh FINORA; lỗi kết nối ném exception. */
    Status lookup(UUID withdrawalId);

    record PayoutCommand(UUID withdrawalId, BigDecimal amount, String currency, String bankCode, String accountNumber,
            String accountHolderName) {
    }

    record Submission(boolean accepted, String reference, String errorCode, String errorMessage) {
        public static Submission accepted(String reference) {
            return new Submission(true, reference, null, null);
        }

        public static Submission rejected(String code, String message) {
            return new Submission(false, null, code, message);
        }
    }

    enum Outcome { SUCCEEDED, FAILED, PENDING, NOT_FOUND }

    record Status(Outcome outcome, String reference, String errorCode, String errorMessage) {
    }
}
