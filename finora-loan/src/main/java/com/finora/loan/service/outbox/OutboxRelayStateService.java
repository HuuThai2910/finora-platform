package com.finora.loan.service.outbox;

import java.util.List;
import java.util.UUID;

/**
 * Trạng thái relay của outbox event; mỗi thay đổi là một transaction ngắn, tách khỏi thao tác broker.
 */
public interface OutboxRelayStateService {

    /** ID các event đến hạn relay (chờ gửi hoặc đang gửi nhưng mất lease), tối đa một batch. */
    List<Long> dueIds();

    /** Khoá event và cấp claim token mới; {@code null} khi event không còn đến lượt gửi. */
    ClaimedOutboxMessage claim(Long id);

    /** Đánh dấu đã publish cho lần claim mang {@code claimToken}. */
    void markPublished(Long id, UUID claimToken);

    /** Ghi lỗi publish cho lần claim mang {@code claimToken}; lỗi retry được thì hẹn lần sau theo backoff. */
    void markFailed(
            Long id,
            UUID claimToken,
            String errorCode,
            String errorDetail,
            boolean retryable
    );
}
