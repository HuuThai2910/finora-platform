package com.finora.loan.integration.profile;

/**
 * Không đọc được họ tên và CCCD đã che của người vay từ finora-user (User lỗi, timeout, mạch đang mở, Keycloak không cấp
 * token, thiếu quyền {@code user:identity:read_internal} hoặc User trả 404).
 *
 * <p>Hợp đồng chưa được lập và không bao giờ in tên đoán; event {@code LoanFullyFunded} được thử lại theo backoff riêng
 * của loại lỗi này ({@code LoanKafkaConsumerConfiguration}).</p>
 */
public class BorrowerIdentityUnavailableException extends RuntimeException {

    private final String reason;

    public BorrowerIdentityUnavailableException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Mã ngắn để log và đối soát, không chứa dữ liệu cá nhân. */
    public String getReason() {
        return reason;
    }
}
