package com.finora.payment.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code WithdrawalCompleted.v1} / {@code WithdrawalFailed.v1} (R4): báo người rút tiền về kết quả (Notification, R7).
 * Chỉ mang số tài khoản đã che; không có số tài khoản đầy đủ hay tên chủ tài khoản.
 */
public record WithdrawalEventData(UUID withdrawalId, String ownerType, String ownerId, String amount, String currency,
        String bankCode, String accountNumberMasked, String status, String errorCode, Instant occurredAt) {
}
