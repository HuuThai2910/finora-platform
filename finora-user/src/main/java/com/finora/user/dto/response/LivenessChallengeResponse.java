package com.finora.user.dto.response;

import java.util.List;

/**
 * Thử thách active liveness cấp cho một phiên xác minh.
 *
 * @param sessionId         mã phiên dùng một lần, phải gửi lại khi gọi {@code ekyc-verify}
 * @param actions           chuỗi hành động phải làm **đúng thứ tự** (blink, turn_left, turn_right)
 * @param expiresInSeconds  thời gian sống còn lại của phiên
 */
public record LivenessChallengeResponse(
        String sessionId,
        List<String> actions,
        long expiresInSeconds
) {
}
