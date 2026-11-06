package com.finora.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.gateway.config.ApiLogProperties;
import com.finora.gateway.util.BodyMasker;
import com.finora.gateway.util.CallerResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access log của Gateway: mỗi lượt gọi {@code /api/**} thành một dòng JSON (logger {@code finora.api}) gồm ai gọi, gọi
 * gì, service nào trả lời, mã trạng thái, thời gian, body gửi lên (đã che) và mã/câu lỗi trả về. Gateway không lưu
 * gì: trên VM Azure, Docker đẩy dòng log sang Azure Monitor Logs (workspace {@code finora-logs}) để tra bằng KQL.
 * <p>
 * Chạy trước {@code InternalAiRouteBlockingFilter} để cả lượt dò route nội bộ bị chặn cũng có trong log. Bỏ qua
 * preflight {@code OPTIONS}.
 * <p>
 * Logger mang tên {@code finora.api} (không theo tên class) vì KQL nhật ký hoạt động ở finora-user lọc dòng log
 * theo tên này.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j(topic = "finora.api")
public class ApiLogFilter extends OncePerRequestFilter {

    private static final Pattern ERROR_DESCRIPTION = Pattern.compile("error_description=\"([^\"]*)\"");
    private static final String TRACE_HEADER = "X-Trace-Id";

    private final ApiLogProperties apiLogProperties;
    private final BodyMasker bodyMasker;
    private final CallerResolver callerResolver;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !apiLogProperties.enabled()
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path == null
                || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request, apiLogProperties.bodyLimit());
        CapturingResponseWrapper wrappedResponse = new CapturingResponseWrapper(response, apiLogProperties.bodyLimit());
        Throwable failure = null;
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                log.info(objectMapper.writeValueAsString(line(wrappedRequest, wrappedResponse, failure, durationMs)));
            } catch (JsonProcessingException | RuntimeException ignored) {
                // Access log không bao giờ được làm hỏng request thật.
            }
        }
    }

    /** Nội dung một dòng log; trường rỗng bị bỏ cho dòng ngắn. Body lỗi không ghi nguyên văn, chỉ mã và câu lỗi. */
    Map<String, Object> line(ContentCachingRequestWrapper request, CapturingResponseWrapper response, Throwable failure,
            long durationMs) {
        int status = response.getStatus();
        if (failure != null && status < 400) status = 500;
        ErrorInfo errorInfo = status >= 400 ? errorInfo(responseBody(response), response, failure) : ErrorInfo.NONE;
        Object routeId = request.getAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR);
        CallerResolver.Caller caller = callerResolver.resolve(request);
        String traceHeader = response.getHeader(TRACE_HEADER);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("method", request.getMethod());
        line.put("path", request.getRequestURI());
        line.put("query", bodyMasker.maskQuery(request.getQueryString()));
        line.put("service", routeId != null ? routeId.toString() : "gateway");
        line.put("status", status);
        line.put("durationMs", durationMs);
        line.put("user", caller.name());
        line.put("role", caller.role());
        line.put("client", client(request.getHeader(HttpHeaders.USER_AGENT)));
        line.put("ip", clientIp(request));
        line.put("errorCode", errorInfo.code());
        line.put("errorMessage", errorInfo.message());
        line.put("traceId", traceHeader != null ? traceHeader : errorInfo.traceId());
        line.put("requestBody", requestBody(request));
        // Web đánh dấu lượt tự làm mới định kỳ bằng header này; nhật ký hoạt động mặc định ẩn các lượt đó.
        if (isBackground(request.getHeader(BACKGROUND_HEADER))) line.put("background", true);
        line.values().removeIf(Objects::isNull);
        return line;
    }

    private String requestBody(ContentCachingRequestWrapper request) {
        String contentType = request.getContentType();
        long length = request.getContentLengthLong();
        if (contentType != null && isBinary(contentType)) {
            return "[" + contentType.split(";")[0] + (length >= 0 ? ", " + length + " byte" : "") + "]";
        }
        byte[] bytes = request.getContentAsByteArray();
        if (bytes.length == 0) return null;
        boolean truncated = length > bytes.length || bytes.length >= apiLogProperties.bodyLimit();
        return bodyMasker.mask(new String(bytes, StandardCharsets.UTF_8), contentType, truncated,
                apiLogProperties.loggedBodyLimit());
    }

    private static String responseBody(CapturingResponseWrapper response) {
        String encoding = response.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
            return "[body nén " + encoding + "]";
        }
        String contentType = response.getContentType();
        if (contentType != null && isBinary(contentType)) return "[" + contentType.split(";")[0] + "]";
        return response.capturedText();
    }

    /**
     * Mã và câu lỗi theo thứ tự: body lỗi chuẩn ({@code code}/{@code message} của Spring, {@code detail} của FastAPI)
     * → header {@code WWW-Authenticate} của 401/403 do Spring Security trả về không có body → exception của Gateway
     * khi không gọi được service phía sau.
     */
    private ErrorInfo errorInfo(String body, CapturingResponseWrapper response, Throwable failure) {
        String code = null;
        String message = null;
        String traceId = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode json = objectMapper.readTree(body);
                if (json != null && json.isObject()) {
                    code = text(json, "code", "error");
                    message = text(json, "message", "detail", "error_description", "title");
                    traceId = text(json, "traceId");
                }
            } catch (Exception notJson) {
                message = BodyMasker.cut(body.strip(), 300);
            }
        }
        if (message == null) {
            message = challengeMessage(response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        }
        if (failure != null) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (code == null) code = root.getClass().getSimpleName();
            if (message == null) {
                message = root.getMessage() != null && !root.getMessage().isBlank()
                        ? root.getMessage()
                        : "Không kết nối được service phía sau (service đang tắt hoặc không phản hồi)";
            }
        }
        return new ErrorInfo(code, message == null ? null : BodyMasker.cut(message, 500), traceId);
    }

    /**
     * Câu lỗi từ header {@code WWW-Authenticate} của Spring Security: lấy {@code error_description} nếu có (token hết
     * hạn, sai chữ ký…); chỉ có {@code Bearer} trơn nghĩa là request không mang token.
     */
    static String challengeMessage(String challenge) {
        if (challenge == null || challenge.isBlank()) return null;
        Matcher description = ERROR_DESCRIPTION.matcher(challenge);
        if (description.find()) return description.group(1);
        return "Bearer".equalsIgnoreCase(challenge.strip()) ? "Chưa đăng nhập (request không có access token)" : challenge;
    }

    private static String text(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode value = json.get(name);
            if (value == null || value.isNull()) continue;
            String text = value.isValueNode() ? value.asText() : value.toString();
            if (!text.isBlank()) return text;
        }
        return null;
    }

    private static boolean isBinary(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("multipart/") || type.startsWith("image/") || type.startsWith("video/")
                || type.startsWith("audio/") || type.contains("octet-stream") || type.contains("pdf")
                || type.contains("zip");
    }

    /** Header web gửi kèm các lượt gọi tự động (tự làm mới định kỳ), không phải thao tác của người dùng. */
    static final String BACKGROUND_HEADER = "X-Finora-Background";

    static boolean isBackground(String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim()));
    }

    static String client(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "OTHER";
        String agent = userAgent.toLowerCase(Locale.ROOT);
        if (agent.contains("okhttp") || agent.contains("cfnetwork") || agent.contains("darwin")
                || agent.contains("expo")) {
            return "MOBILE";
        }
        return agent.contains("mozilla") ? "WEB" : "OTHER";
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
    }

    private record ErrorInfo(String code, String message, String traceId) {
        static final ErrorInfo NONE = new ErrorInfo(null, null, null);
    }
}
