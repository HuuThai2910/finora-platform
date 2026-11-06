package com.finora.notification.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.notification.domain.enums.DeferredEventStatus;
import com.finora.notification.domain.enums.PushDeliveryStatus;
import com.finora.notification.dto.response.DeferredEventAdminResponse;
import com.finora.notification.dto.response.PushDeliveryAdminResponse;
import com.finora.notification.service.operations.NotificationOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vận hành thông báo cho ADMIN và nhân viên có quyền Đối soát & sự cố (quyền kiểm ở {@code NotificationSecurityConfig}).
 * Đặt dưới {@code /api/v1/notifications} để Gateway hiện có đưa về Notification; {@code /api/v1/admin/**} thuộc route
 * của Loan.
 */
@RestController
@RequestMapping("/api/v1/notifications/admin")
@RequiredArgsConstructor
public class NotificationOperationsController {

    private final NotificationOperationsService notificationOperationsService;

    /** Mặc định danh sách dead-letter ({@code DEAD}) mới nhất trước, phân trang tối đa 100. */
    @GetMapping("/push-deliveries")
    public ResponseEntity<PageResponse<PushDeliveryAdminResponse>> pushDeliveries(
            @RequestParam(defaultValue = "DEAD") PushDeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationOperationsService.pushDeliveries(status, page, size));
    }

    @PostMapping("/push-deliveries/{id}/requeue")
    public ResponseEntity<PushDeliveryAdminResponse> requeue(@PathVariable Long id) {
        return ResponseEntity.ok(notificationOperationsService.requeue(id, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/deferred-events")
    public ResponseEntity<PageResponse<DeferredEventAdminResponse>> deferredEvents(
            @RequestParam(defaultValue = "EXPIRED") DeferredEventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationOperationsService.deferredEvents(status, page, size));
    }
}
