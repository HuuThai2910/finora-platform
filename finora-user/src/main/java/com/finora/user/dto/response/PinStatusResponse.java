package com.finora.user.dto.response;

import java.time.Instant;

/**
 * Trạng thái mã PIN của người dùng hiện tại — mobile dựa vào đây để chọn màn
 * tạo PIN hay nhập PIN.
 */
public record PinStatusResponse(
        boolean hasPin,
        boolean locked,
        Instant lockedUntil,
        int remainingAttempts
) {
}
