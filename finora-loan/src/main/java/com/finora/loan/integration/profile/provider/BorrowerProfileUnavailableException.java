package com.finora.loan.integration.profile.provider;

/**
 * Không lấy được trạng thái eKYC của người vay từ nguồn (User lỗi, timeout, từ chối token hoặc trả dữ liệu lệch người).
 * Hồ sơ không được tạo; người vay gửi lại cùng {@code Idempotency-Key} khi nguồn hoạt động lại.
 */
public class BorrowerProfileUnavailableException extends RuntimeException {

    private final String reason;

    public BorrowerProfileUnavailableException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Mã ngắn để log và đối soát, không chứa dữ liệu cá nhân. */
    public String getReason() {
        return reason;
    }
}
