package com.finora.notification.controller;

import com.finora.notification.dto.response.InAppNotificationResponse;
import com.finora.notification.service.InAppNotificationQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Hộp thư trong app ({@code /api/v1/notifications}, JWT người dùng). Danh sách vẫn trả mảng tối đa {@code limit} tin
 * (1–100) như contract mobile/E2E hiện hành.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class InAppNotificationController {
    private final InAppNotificationQueryService service;

    @GetMapping
    public List<InAppNotificationResponse> list(
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    /** Đánh dấu mọi tin của người đang đăng nhập là đã đọc; trả số tin vừa đổi. */
    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead());
    }
}
