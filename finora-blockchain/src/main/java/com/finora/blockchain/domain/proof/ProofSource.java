package com.finora.blockchain.domain.proof;

import java.time.Instant;
import java.util.UUID;

/**
 * Event nguồn đã sinh yêu cầu proof. {@code eventType/eventVersion/occurredAt} cho người kiểm toán biết artifact nào
 * cần lấy ở service nguồn để tính lại hash; bản ghi trước W3D không có các field này (null).
 */
public record ProofSource(
        String service,
        UUID eventId,
        String eventType,
        Integer eventVersion,
        Instant occurredAt
) {
}
