package com.finora.notification.service.intake;

import java.util.Optional;

/**
 * Cửa nhận event duy nhất của Notification (F09, D60): idempotent theo {@code eventId}, chọn handler theo loại/version,
 * lưu thông báo + dấu đã xử lý trong cùng một transaction.
 */
public interface NotificationIntakeService {

    /** Event mới từ Kafka. */
    IntakeResult accept(InboundEvent event);

    /**
     * Worker thử lại một event đang chờ. Khóa dòng bằng SKIP LOCKED: hai worker không xử lý cùng event. Hết thời gian
     * chờ thì gửi cho người đã biết, ghi {@code EXPIRED_PARTIAL} và giữ dòng ở trạng thái EXPIRED cho vận hành.
     */
    Optional<IntakeResult> retryDeferred(Long deferredId);

    /** Event chờ bị lỗi contract khi xử lý lại (không nên xảy ra): dừng thử, giữ dòng EXPIRED để điều tra. */
    void abandonDeferred(Long deferredId, String reason);
}
