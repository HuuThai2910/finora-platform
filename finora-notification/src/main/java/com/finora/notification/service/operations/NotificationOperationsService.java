package com.finora.notification.service.operations;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.notification.domain.DeferredEvent;
import com.finora.notification.domain.DeferredEventStatus;
import com.finora.notification.domain.PushDelivery;
import com.finora.notification.domain.PushDeliveryStatus;
import com.finora.notification.dto.response.DeferredEventAdminResponse;
import com.finora.notification.dto.response.PushDeliveryAdminResponse;
import com.finora.notification.repository.DeferredEventRepository;
import com.finora.notification.repository.PushDeliveryRepository;
import com.finora.notification.repository.PushDeviceRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thao tác vận hành cho ADMIN: xem push dead-letter/thất bại, gửi lại sau khi sửa nguyên nhân, xem event chờ người
 * nhận. Không có thao tác sửa nội dung thông báo hay người nhận.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOperationsService {

    static final int MAX_PAGE_SIZE = 100;

    private final PushDeliveryRepository deliveries;
    private final PushDeviceRepository devices;
    private final DeferredEventRepository deferred;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<PushDeliveryAdminResponse> pushDeliveries(PushDeliveryStatus status, int page, int size) {
        Page<PushDelivery> values = deliveries.findByStatusOrderByUpdatedAtDescIdDesc(status, pageRequest(page, size));
        return page(values.map(PushDeliveryAdminResponse::from));
    }

    /** Gửi lại một push DEAD/FAILED; thiết bị phải còn hoạt động (token đã thu hồi thì không còn chỗ gửi). */
    @Transactional
    public PushDeliveryAdminResponse requeue(Long deliveryId, String actorId) {
        PushDelivery delivery = deliveries.findByIdForUpdate(deliveryId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "PUSH_DELIVERY_NOT_FOUND", "Không tìm thấy lần gửi push"));
        if (!delivery.isRequeueable()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PUSH_DELIVERY_NOT_REQUEUEABLE",
                    "Chỉ gửi lại được push ở trạng thái DEAD hoặc FAILED");
        }
        boolean deviceActive = devices.findById(delivery.getDeviceId()).map(device -> device.isActive()).orElse(false);
        if (!deviceActive) {
            throw new BusinessException(HttpStatus.CONFLICT, "PUSH_DEVICE_REVOKED",
                    "Thiết bị đã thôi nhận push; không gửi lại được");
        }
        delivery.requeue(clock.instant());
        log.info("Admin gửi lại push: deliveryId={}, actorId={}", deliveryId, actorId);
        return PushDeliveryAdminResponse.from(delivery);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeferredEventAdminResponse> deferredEvents(DeferredEventStatus status, int page, int size) {
        Page<DeferredEvent> values = deferred.findByStatusOrderByIdDesc(status, pageRequest(page, size));
        return page(values.map(DeferredEventAdminResponse::from));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    private static <T> PageResponse<T> page(Page<T> values) {
        return PageResponse.<T>builder()
                .content(values.getContent())
                .page(values.getNumber())
                .size(values.getSize())
                .totalElements(values.getTotalElements())
                .totalPages(values.getTotalPages())
                .last(values.isLast())
                .build();
    }
}
