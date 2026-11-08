package com.finora.notification.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Payload ví/rút tiền và khoản trả kỳ đang giữ của Payment mà Notification đọc. */
public final class PaymentWalletEvents {

    private PaymentWalletEvents() {
    }

    /**
     * {@code WithdrawalCompleted.v1}/{@code WithdrawalFailed.v1} (Payment Wave 3). {@code ownerId} là ID hồ sơ (claim
     * {@code user_id}); {@code accountNumberMasked} đã che phía Payment, không chứa số tài khoản đầy đủ.
     */
    public record WithdrawalResult(String withdrawalId, String ownerType, String ownerId, String amount,
            String currency, String bankCode, String accountNumberMasked, String status, String errorCode,
            Instant occurredAt) {}

    /**
     * {@code BankLinkOtpRequested.v1} (BL-001): ngân hàng giả lập của Payment "gửi SMS" mã OTP liên kết tài khoản.
     * {@code accountNumberMasked} đã che phía Payment; mã OTP chỉ được ghi vào câu trong app, không vào push.
     */
    public record BankLinkOtpRequested(String linkRequestId, String ownerId, String bankCode, String bankName,
            String accountNumberMasked, String otp, Instant expiresAt, Instant occurredAt) {}

    /**
     * {@code RepaymentCollected.v1} (đề xuất P5, chưa có producer): Payment đã thu tiền một kỳ trước hạn và giữ tới
     * {@code dueDate} (D16).
     */
    public record RepaymentCollected(UUID repaymentId, Long loanApplicationId, String borrowerId, String repaymentType,
            String amount, String currency, LocalDate dueDate, Instant collectedAt) {}

    /** {@code RepaymentCancelled.v1} (đề xuất P5): người vay hủy khoản đang giữ, tiền đã hoàn về ví. */
    public record RepaymentCancelled(UUID repaymentId, Long loanApplicationId, String borrowerId, LocalDate dueDate,
            Instant cancelledAt) {}
}
