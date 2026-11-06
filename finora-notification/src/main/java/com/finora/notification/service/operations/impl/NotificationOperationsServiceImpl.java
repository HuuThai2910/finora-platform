package com.finora.notification.service.operations.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.notification.domain.entity.DeferredEvent;
import com.finora.notification.domain.entity.PushDelivery;
import com.finora.notification.domain.enums.DeferredEventStatus;
import com.finora.notification.domain.enums.PushDeliveryStatus;
import com.finora.notification.dto.response.DeferredEventAdminResponse;
import com.finora.notification.dto.response.PushDeliveryAdminResponse;
import com.finora.notification.repository.DeferredEventRepository;
import com.finora.notification.repository.PushDeliveryRepository;
import com.finora.notification.repository.PushDeviceRepository;
import com.finora.notification.service.operations.NotificationOperationsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Thao tác vận hành cho ADMIN: xem push dead-letter/thất bại, gửi lại sau khi sửa nguyên nhân, xem event chờ người
 * nhận. Không có thao tác sửa nội dung thông báo hay người nhận.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOperationsServiceImpl implements NotificationOperationsService {

    static final int MAX_PAGE_SIZE = 100;

    private final PushDeliveryRepository pushDeliveryRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final DeferredEventRepository deferredEventRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PushDeliveryAdminResponse> pushDeliveries(PushDeliveryStatus status, int page, int size) {
        Page<PushDelivery> deliveryPage = pushDeliveryRepository.findByStatusOrderByUpdatedAtDescIdDesc(status,
                pageRequest(page, size));
        return toPageResponse(deliveryPage.map(PushDeliveryAdminResponse::from));
    }

    @Override
    @Transactional
    public PushDeliveryAdminResponse requeue(Long deliveryId, String actorId) {
        PushDelivery delivery = pushDeliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PUSH_DELIVERY_NOT_FOUND",
                        "Không tìm thấy lần gửi push"));
        if (!delivery.isRequeueable()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PUSH_DELIVERY_NOT_REQUEUEABLE",
                    "Chỉ gửi lại được push ở trạng thái DEAD hoặc FAILED");
        }
        boolean deviceActive = pushDeviceRepository.findById(delivery.getDeviceId())
                .map(device -> device.isActive()).orElse(false);
        if (!deviceActive) {
            throw new BusinessException(HttpStatus.CONFLICT, "PUSH_DEVICE_REVOKED",
                    "Thiết bị đã thôi nhận push; không gửi lại được");
        }
        delivery.requeue(clock.instant());
        log.info("Admin gửi lại push: deliveryId={}, actorId={}", deliveryId, actorId);
        return PushDeliveryAdminResponse.from(delivery);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeferredEventAdminResponse> deferredEvents(DeferredEventStatus status, int page, int size) {
        Page<DeferredEvent> deferredEventPage = deferredEventRepository.findByStatusOrderByIdDesc(status,
                pageRequest(page, size));
        return toPageResponse(deferredEventPage.map(DeferredEventAdminResponse::from));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}
