package com.finora.gateway.config;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS xử lý tập trung tại Gateway cho client gọi thẳng qua cổng 8080
 * (Expo web, web dev chạy ở cổng khác, máy trong LAN).
 *
 * <p>Gateway MVC tự trả lời preflight OPTIONS, không chuyển xuống service, nên
 * thiếu cấu hình này thì trình duyệt nhận 403 "Invalid CORS request".
 *
 * <p>Sau khi Gateway đã kiểm tra origin, header {@code Origin} bị bỏ trước khi
 * chuyển tiếp: service phía sau không chạy CORS lần hai (tránh 403 vì origin
 * không có trong danh sách của service, và tránh trùng Access-Control-Allow-Origin).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://192.168.*.*:*"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public RequestHttpHeadersFilter removeOriginBeforeProxy() {
        return (headers, request) -> {
            HttpHeaders filtered = new HttpHeaders();
            filtered.putAll(headers);
            filtered.remove(HttpHeaders.ORIGIN);
            return filtered;
        };
    }

    /** Phòng khi service phía sau vẫn tự gắn header CORS: chỉ giữ header do Gateway đặt. */
    @Bean
    public ResponseHttpHeadersFilter removeDownstreamCorsHeaders() {
        return (headers, response) -> {
            HttpHeaders filtered = new HttpHeaders();
            headers.forEach((name, values) -> {
                if (!name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())) {
                    filtered.put(name, values);
                }
            });
            return filtered;
        };
    }
}
