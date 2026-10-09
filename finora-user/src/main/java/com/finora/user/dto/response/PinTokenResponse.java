package com.finora.user.dto.response;

import java.time.Instant;

/**
 * Pin-token gửi kèm header {@code X-Pin-Token} ở đúng một lời gọi nhạy cảm.
 */
public record PinTokenResponse(
        String pinToken,
        Instant expiresAt
) {
}
