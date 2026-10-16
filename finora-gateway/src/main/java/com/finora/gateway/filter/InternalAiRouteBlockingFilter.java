package com.finora.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Chặn mọi request từ ngoài vào {@code /api/v1/ai/ekyc/**} (EKYC-002 mục 6).
 * <p>
 * Các route eKYC của finora-ai (đọc thẻ, liveness) chỉ dành cho finora-user gọi nội bộ; trước đây
 * route OCR mở ra internet qua gateway nên ai cũng gọi được để đốt quota AI. Route
 * {@code /api/v1/ai/credit/**} (finora-web dùng) vẫn đi qua bình thường.
 * <p>
 * Đường dẫn được chuẩn hoá trước khi so: giải mã phần trăm ({@code %65kyc}), bỏ tham số đoạn
 * ({@code ;x=y}), gộp {@code //}, bỏ {@code /./}, xử lý {@code /../}, không phân biệt hoa thường —
 * vì FastAPI phía sau giải mã và khớp route theo cách riêng, một biến thể lọt qua là lộ route.
 * Trả 404 (không phải 403) để không xác nhận route tồn tại.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAiRouteBlockingFilter extends OncePerRequestFilter {

    static final String BLOCKED_PREFIX = "/api/v1/ai/ekyc";

    private static final byte[] NOT_FOUND_BODY =
            "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Không tìm thấy tài nguyên\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBlocked(request.getRequestURI(), request.getContextPath())
                || isBlocked(servletPath(request), "")) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(NOT_FOUND_BODY.length);
            response.getOutputStream().write(NOT_FOUND_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Đường dẫn đã chuẩn hoá thuộc {@code /api/v1/ai/ekyc} hoặc nằm dưới nó. */
    static boolean isBlocked(String rawPath, String contextPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        String path = rawPath;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String normalized = normalize(path);
        return normalized.equals(BLOCKED_PREFIX) || normalized.startsWith(BLOCKED_PREFIX + "/");
    }

    /**
     * Giải mã nhiều lần (tối đa 3) để bắt cả mã hoá lồng {@code %2565}, rồi chuẩn hoá từng đoạn. Chuỗi
     * mã hoá hỏng giữ nguyên dạng thô để vẫn được so tiếp.
     */
    static String normalize(String path) {
        String decoded = path;
        for (int i = 0; i < 3; i++) {
            String next;
            try {
                next = UriUtils.decode(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                break;
            }
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decoded.replace('\\', '/').split("/")) {
            int parameterStart = segment.indexOf(';');
            String clean = (parameterStart >= 0 ? segment.substring(0, parameterStart) : segment).trim();
            if (clean.isEmpty() || clean.equals(".")) {
                continue;
            }
            if (clean.equals("..")) {
                segments.pollLast();
                continue;
            }
            segments.addLast(clean.toLowerCase(Locale.ROOT));
        }
        return "/" + String.join("/", segments);
    }

    private static String servletPath(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servletPath + pathInfo;
    }
}
