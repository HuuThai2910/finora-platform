package com.finora.loan.service.funding;

import java.time.Instant;

/**
 * Đóng đợt gọi vốn trước giải ngân và yêu cầu Investment bồi hoàn các khoản giữ liên quan.
 *
 * <p>Chạy trong cùng transaction với thay đổi nguồn (Contract chuyển DECLINED/EXPIRED hoặc Saga chuyển
 * PAYMENT_FAILED) để trạng thái gọi vốn và outbox cùng commit/rollback. Loan chỉ phát sự kiện; việc hủy
 * commitment thuộc Investment và việc trả tiền giữ thuộc Payment theo đúng SoR.</p>
 */
public interface LoanFundingCancellationService {

    /** Lý do hủy: người vay hoặc nhà đầu tư từ chối ký hợp đồng. */
    String CONTRACT_DECLINED = "CONTRACT_DECLINED";

    /** Lý do hủy: hợp đồng hết hạn ký. */
    String CONTRACT_EXPIRED = "CONTRACT_EXPIRED";

    /** Lý do hủy: Payment báo giải ngân thất bại khi tiền chưa được chuyển. */
    String DISBURSEMENT_FAILED = "DISBURSEMENT_FAILED";

    /** Lý do hủy: người vay rút hồ sơ khi đợt gọi vốn còn mở. */
    String BORROWER_WITHDREW = "BORROWER_WITHDREW";

    /**
     * Khóa hồ sơ, chuyển {@code REQUESTED/FULLY_FUNDED -> CANCELLED} và ghi outbox publishable đúng một lần.
     *
     * @return {@code true} nếu vừa phát event bồi hoàn; {@code false} nếu đã hủy trước đó
     */
    boolean cancel(Long applicationId, String contractNumber, String reasonCode, Instant now);
}
