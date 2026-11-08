package com.finora.payment.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code BankLinkOtpRequested.v1} (BL-001): ngân hàng giả lập "gửi SMS" mã OTP liên kết tài khoản qua Notification. Chỉ
 * phát khi provider liên kết là {@code mock}; ngân hàng thật tự gửi OTP tới số điện thoại khách hàng, Payment không
 * bao giờ biết mã. Không mang số tài khoản đầy đủ.
 */
public record BankLinkOtpEventData(UUID linkRequestId, String ownerId, String bankCode, String bankName,
        String accountNumberMasked, String otp, Instant expiresAt, Instant occurredAt) {
}
