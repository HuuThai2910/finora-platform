package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.user.config.ActivityLogProperties;
import com.finora.user.dto.response.ActivityLogEntryResponse;
import com.finora.user.dto.response.ActivityLogPageResponse;
import com.finora.user.integration.loganalytics.LogAnalyticsClient;
import com.finora.user.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Nhật ký hoạt động của web quản trị: lượt gọi API qua Gateway, đọc từ Azure Log Analytics.
 * <p>
 * Mỗi lần tìm chạy hai câu KQL dựng ở {@link ActivityLogKql}: một câu đếm và một câu lấy dòng; trang cắt ở đây vì
 * Log Analytics không có offset.
 */
@Service
@RequiredArgsConstructor
public class ActivityLogServiceImpl implements ActivityLogService {

    private static final int MAX_PAGE_SIZE = 200;

    private final LogAnalyticsClient logAnalyticsClient;
    private final ActivityLogProperties activityLogProperties;

    /**
     * Log Analytics không có offset: lấy {@code offset + size} dòng mới nhất rồi cắt trang ở đây, nên trang sâu nhất
     * dừng ở {@code maxRows}. Số đếm lấy bằng một câu {@code summarize} riêng.
     */
    @Override
    public ActivityLogPageResponse search(String range, String method, String outcome, String service, String client,
            String q, int page, int size) {
        return search(new ActivityLogQuery(range, method, outcome, service, client, q, null, null, true), page, size);
    }

    @Override
    public String exportCsv(ActivityLogQuery query) {
        ActivityLogKql.Filters filters = filters(query);
        List<ActivityLogEntryResponse> rows = logAnalyticsClient
                .query(ActivityLogKql.rows(filters, activityLogProperties.getMaxRows())).stream()
                .map(ActivityLogServiceImpl::entry)
                .toList();
        return ActivityLogCsv.write(rows);
    }

    private static ActivityLogKql.Filters filters(ActivityLogQuery query) {
        return new ActivityLogKql.Filters(query.range(), query.method(), query.outcome(), query.service(),
                query.client(), query.q(), query.from(), query.to(), query.includeBackground());
    }

    @Override
    public ActivityLogPageResponse search(ActivityLogQuery query, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        ActivityLogKql.Filters filters = filters(query);
        int offset = Math.min(safePage * safeSize, activityLogProperties.getMaxRows());
        int take = Math.min(offset + safeSize, activityLogProperties.getMaxRows());

        List<Map<String, JsonNode>> stats = logAnalyticsClient.query(ActivityLogKql.stats(filters));
        List<ActivityLogEntryResponse> rows = take <= offset
                ? List.of()
                : logAnalyticsClient.query(ActivityLogKql.rows(filters, take)).stream()
                        .skip(offset)
                        .map(ActivityLogServiceImpl::entry)
                        .toList();

        Map<String, JsonNode> counts = stats.isEmpty() ? Map.of() : stats.get(0);
        return ActivityLogPageResponse.builder()
                .content(rows)
                .totalElements(number(counts, "matched"))
                .page(safePage)
                .size(safeSize)
                .maxRows(activityLogProperties.getMaxRows())
                .total(number(counts, "total"))
                .clientErrors(number(counts, "clientErrors"))
                .serverErrors(number(counts, "serverErrors"))
                .build();
    }

    static ActivityLogEntryResponse entry(Map<String, JsonNode> row) {
        return ActivityLogEntryResponse.builder()
                .at(Instant.parse(row.get("at").asText()))
                .method(text(row, "method"))
                .path(text(row, "path"))
                .query(text(row, "query"))
                .service(text(row, "service"))
                .status((int) number(row, "status"))
                .durationMs(number(row, "durationMs"))
                .user(text(row, "user"))
                .role(text(row, "role"))
                .client(text(row, "client"))
                .ip(text(row, "ip"))
                .errorCode(text(row, "errorCode"))
                .errorMessage(text(row, "errorMessage"))
                .traceId(text(row, "traceId"))
                .requestBody(text(row, "requestBody"))
                .build();
    }

    /** KQL trả chuỗi rỗng cho trường không có trong dòng log; đổi về {@code null} cho web. */
    private static String text(Map<String, JsonNode> row, String column) {
        JsonNode value = row.get(column);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static long number(Map<String, JsonNode> row, String column) {
        JsonNode value = row.get(column);
        return value == null || value.isNull() ? 0 : value.asLong();
    }
}
