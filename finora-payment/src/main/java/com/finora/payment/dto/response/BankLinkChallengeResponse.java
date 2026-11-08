package com.finora.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu liên kết đang chờ OTP. Mã OTP không bao giờ có trong response: ngân hàng gửi qua kênh riêng (bản giả lập gửi
 * qua thông báo của FINORA).
 *
 * @param resendAvailableAt sớm nhất lúc nào được xin gửi lại mã
 * @param resendsLeft       còn bao nhiêu lần xin gửi lại
 */
public record BankLinkChallengeResponse(
        UUID linkRequestId,
        String bankCode,
        String bankName,
        String accountNumberMasked,
        String accountHolderName,
        Instant otpExpiresAt,
        Instant resendAvailableAt,
        int resendsLeft
) {
}
