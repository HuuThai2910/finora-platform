package com.finora.notification.controller;

import com.finora.notification.dto.response.InAppNotificationResponse;
import com.finora.notification.service.InAppNotificationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hộp thư trong app ({@code /api/v1/notifications}, JWT người dùng). Danh sách vẫn trả mảng tối đa {@code limit} tin
 * (1–100) như contract mobile/E2E hiện hành.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class InAppNotificationController {
    private final InAppNotificationQueryService inAppNotificationQueryService;

    @GetMapping
    public ResponseEntity<List<InAppNotificationResponse>> list(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(inAppNotificationQueryService.list(limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", inAppNotificationQueryService.unreadCount()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        inAppNotificationQueryService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    /** Đánh dấu mọi tin của người đang đăng nhập là đã đọc; trả số tin vừa đổi. */
    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead() {
        return ResponseEntity.ok(Map.of("updated", inAppNotificationQueryService.markAllRead()));
    }
}
