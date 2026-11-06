package com.finora.user.controller;
import com.finora.common.exception.BusinessException;
import com.finora.user.dto.response.ActivityLogPageResponse;
import com.finora.user.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Màn Nhật ký hoạt động của web quản trị: mọi lượt gọi API từ web và mobile qua Gateway, đọc từ Azure Log Analytics.
 * <p>
 * Chỉ ADMIN: body gửi lên dù đã che vẫn có dữ liệu cá nhân. Gateway chuyển {@code /api/v1/admin/activity-logs/**}
 * về finora-user (trước route admin của Loan).
 */
@RestController
@RequestMapping("/api/v1/admin/activity-logs")
@RequiredArgsConstructor
public class AdminActivityLogController {

    private final ActivityLogService activityLogService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ActivityLogPageResponse> search(
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean includeBackground,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(activityLogService.search(
                query(range, method, outcome, service, client, q, from, to, includeBackground), page, size));
    }

    /** Xuất CSV theo đúng bộ lọc đang xem (tối đa số dòng giới hạn của trang nhật ký). */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean includeBackground) {
        String csv = activityLogService.exportCsv(
                query(range, method, outcome, service, client, q, from, to, includeBackground));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("nhat-ky-hoat-dong.csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static ActivityLogService.ActivityLogQuery query(String range, String method, String outcome,
            String service, String client, String q, String from, String to, boolean includeBackground) {
        return new ActivityLogService.ActivityLogQuery(range, method, outcome, service, client, q,
                instant(from, "from"), instant(to, "to"), includeBackground);
    }

    /** Thời điểm ISO-8601 có múi giờ; sai định dạng là lỗi đầu vào (400), không để rơi thành 500. */
    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Thời điểm " + field + " không đúng định dạng");
        }
    }
}
