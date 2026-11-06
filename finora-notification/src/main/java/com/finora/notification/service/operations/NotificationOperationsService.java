package com.finora.notification.service.operations;

import com.finora.common.dto.PageResponse;
import com.finora.notification.domain.enums.DeferredEventStatus;
import com.finora.notification.domain.enums.PushDeliveryStatus;
import com.finora.notification.dto.response.DeferredEventAdminResponse;
import com.finora.notification.dto.response.PushDeliveryAdminResponse;

/**
 * Thao tác vận hành cho ADMIN: xem push dead-letter/thất bại, gửi lại sau khi sửa nguyên nhân, xem event chờ người
 * nhận. Không có thao tác sửa nội dung thông báo hay người nhận.
 */
public interface NotificationOperationsService {

    PageResponse<PushDeliveryAdminResponse> pushDeliveries(PushDeliveryStatus status, int page, int size);

    /** Gửi lại một push DEAD/FAILED; thiết bị phải còn hoạt động (token đã thu hồi thì không còn chỗ gửi). */
    PushDeliveryAdminResponse requeue(Long deliveryId, String actorId);

    PageResponse<DeferredEventAdminResponse> deferredEvents(DeferredEventStatus status, int page, int size);
}
