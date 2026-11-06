package com.finora.user.service.impl;
import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Câu KQL đọc access log của Gateway. Bộ lọc chỉ nhận giá trị trong danh sách cho phép; chữ tìm kiếm tự do được thoát
 * thành chuỗi KQL, nên người gọi không chèn được câu lệnh.
 */
final class ActivityLogKql {

    private static final Map<String, String> RANGES = Map.of("1h", "1h", "24h", "24h", "7d", "7d", "30d", "30d");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> CLIENTS = Set.of("WEB", "MOBILE", "OTHER");
    private static final Pattern SERVICE = Pattern.compile("[a-z][a-z-]{0,39}");
    private static final int MAX_SEARCH = 100;

    /** Khoảng tự chọn dài nhất, theo thời gian lưu mặc định của Log Analytics. */
    static final Duration MAX_CUSTOM_RANGE = Duration.ofDays(31);

    /** Dòng log của Gateway, tách JSON; bỏ lượt gọi của chính trang nhật ký (tự làm mới định kỳ). */
    private static final String BASE = """
            Syslog
            | where %s
            | where Facility == "local0" and ProcessName startswith "finora-gateway" and SyslogMessage has "finora.api"
            | extend api = parse_json(extract(@"(\\{.*\\})\\s*$", 1, SyslogMessage))
            | project at = TimeGenerated, method = tostring(api.method), path = tostring(api.path),
                query = tostring(api.query), service = tostring(api.service), status = toint(api.status),
                durationMs = tolong(api.durationMs), user = tostring(api.user), role = tostring(api.role),
                client = tostring(api.client), ip = tostring(api.ip), errorCode = tostring(api.errorCode),
                errorMessage = tostring(api.errorMessage), traceId = tostring(api.traceId),
                requestBody = tostring(api.requestBody), background = tobool(api.background)
            | where not(path startswith "/api/v1/admin/activity-logs")
            """;

    /** Lượt gọi web tự gửi định kỳ (header X-Finora-Background, Gateway ghi {@code background}). */
    private static final String HIDE_BACKGROUND = "| where isnull(background) or not(background)\n";

    private ActivityLogKql() {
    }

    /**
     * @param from              đầu khoảng tự chọn; có cả {@code from} và {@code to} thì bỏ qua {@code range}
     * @param includeBackground {@code true} thì giữ cả các lượt gọi tự động
     */
    record Filters(String range, String method, String outcome, String service, String client, String q,
            Instant from, Instant to, boolean includeBackground) {

        Filters(String range, String method, String outcome, String service, String client, String q) {
            this(range, method, outcome, service, client, q, null, null, true);
        }
    }

    static String rows(Filters filters, int take) {
        return base(filters) + where(filters) + "| order by at desc\n| take " + take;
    }

    /** Tổng của khoảng thời gian (không lọc) để làm ô tổng quan, kèm số dòng khớp bộ lọc để phân trang. */
    static String stats(Filters filters) {
        String condition = condition(filters);
        return base(filters) + "| summarize total = count(), clientErrors = countif(status >= 400 and status < 500), "
                + "serverErrors = countif(status >= 500), matched = countif(" + (condition.isEmpty() ? "true" : condition)
                + ")";
    }

    private static String base(Filters filters) {
        String hide = filters.includeBackground() ? "" : HIDE_BACKGROUND;
        if (filters.from() != null || filters.to() != null) {
            if (filters.from() == null || filters.to() == null) throw invalid("Cần chọn đủ ngày bắt đầu và kết thúc");
            if (!filters.from().isBefore(filters.to())) throw invalid("Ngày bắt đầu phải trước ngày kết thúc");
            if (Duration.between(filters.from(), filters.to()).compareTo(MAX_CUSTOM_RANGE) > 0) {
                throw invalid("Chỉ xem được tối đa 31 ngày một lần");
            }
            // Instant.toString luôn là ISO-8601 UTC (chỉ chữ số, '-', ':', 'T', 'Z', '.'), không chèn được câu lệnh.
            return BASE.formatted("TimeGenerated between (datetime(" + filters.from() + ") .. datetime("
                    + filters.to() + "))") + hide;
        }
        String range = filters.range() == null || filters.range().isBlank() ? "24h" : filters.range();
        String ago = RANGES.get(range);
        if (ago == null) throw invalid("Khoảng thời gian không hợp lệ");
        return BASE.formatted("TimeGenerated > ago(" + ago + ")") + hide;
    }

    private static String where(Filters filters) {
        String condition = condition(filters);
        return condition.isEmpty() ? "" : "| where " + condition + "\n";
    }

    static String condition(Filters filters) {
        List<String> parts = new ArrayList<>();
        if (hasText(filters.method())) {
            String method = filters.method().toUpperCase(Locale.ROOT);
            if ("WRITE".equals(method)) parts.add("method !in (\"GET\", \"HEAD\")");
            else if (METHODS.contains(method)) parts.add("method == \"" + method + "\"");
            else throw invalid("Thao tác không hợp lệ");
        }
        if (hasText(filters.outcome())) {
            parts.add(switch (filters.outcome().toUpperCase(Locale.ROOT)) {
                case "SUCCESS" -> "status < 400";
                case "CLIENT_ERROR" -> "status >= 400 and status < 500";
                case "SERVER_ERROR" -> "status >= 500";
                case "ERROR" -> "status >= 400";
                default -> throw invalid("Kết quả lọc không hợp lệ");
            });
        }
        if (hasText(filters.service())) {
            if (!SERVICE.matcher(filters.service()).matches()) throw invalid("Service không hợp lệ");
            parts.add("service == \"" + filters.service() + "\"");
        }
        if (hasText(filters.client())) {
            String client = filters.client().toUpperCase(Locale.ROOT);
            if (!CLIENTS.contains(client)) throw invalid("Ứng dụng không hợp lệ");
            parts.add("client == \"" + client + "\"");
        }
        if (hasText(filters.q())) {
            String needle = literal(filters.q().strip());
            parts.add("(path contains " + needle + " or user contains " + needle + " or errorCode contains " + needle
                    + " or errorMessage contains " + needle + " or query contains " + needle
                    + " or traceId contains " + needle + ")");
        }
        return String.join(" and ", parts);
    }

    /** Chuỗi KQL trong nháy kép: thoát {@code \} và {@code "}, bỏ ký tự điều khiển, giới hạn độ dài. */
    static String literal(String value) {
        String cut = value.length() > MAX_SEARCH ? value.substring(0, MAX_SEARCH) : value;
        StringBuilder out = new StringBuilder("\"");
        for (char c : cut.toCharArray()) {
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (!Character.isISOControl(c)) out.append(c);
        }
        return out.append('"').toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
