package com.finora.gateway.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;

/**
 * HTTP client Gateway dùng để chuyển tiếp request xuống service: ép HTTP/1.1.
 *
 * <p>Mặc định Gateway MVC 4.1 dùng {@code java.net.http.HttpClient} với phiên bản HTTP/2. Với địa chỉ
 * {@code http://} (mạng nội bộ), client này gửi kèm {@code Upgrade: h2c} để xin nâng cấp lên HTTP/2.
 * Tomcat của các service Java bỏ qua lời xin đó, nhưng uvicorn của finora-ai (bộ phân tích httptools)
 * coi phần sau header là dữ liệu của giao thức mới và bỏ mất body: mọi POST/PUT JSON tới
 * {@code /api/v1/ai/**} qua Gateway đến nơi với body rỗng và bị FastAPI trả 422 "Field required"
 * (lỗi ghi nhận ngày 2026-09-03 trong {@code creditScoreApi.ts} của web).
 *
 * <p>Các service phía sau đều chỉ nói HTTP/1.1 nên ép phiên bản không làm mất gì. Bean này thay bean
 * {@code gatewayClientHttpRequestFactory} (khai báo {@code @ConditionalOnMissingBean}) của Gateway,
 * giữ nguyên timeout lấy từ {@code spring.cloud.gateway.mvc.http-client.*} và giữ cách Gateway cho
 * phép chuyển tiếp header {@code Host}.
 */
@Configuration
public class ProxyHttpClientConfig {

    private static final String RESTRICTED_HEADERS_PROPERTY = "jdk.httpclient.allowRestrictedHeaders";

    @Bean
    public ClientHttpRequestFactory http11ClientHttpRequestFactory(GatewayMvcProperties properties) {
        allowHostHeaderLikeGatewayDefault();

        GatewayMvcProperties.HttpClient settings = properties.getHttpClient();
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
        Duration connectTimeout = settings.getConnectTimeout();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        Duration readTimeout = settings.getReadTimeout();
        if (readTimeout != null) {
            factory.setReadTimeout(readTimeout);
        }
        return factory;
    }

    /**
     * Gateway gốc đặt {@code jdk.httpclient.allowRestrictedHeaders=host} trước khi tạo client để có thể
     * giữ Host gốc khi cần. Bean gốc không còn chạy nên làm lại đúng việc đó, không đè giá trị có sẵn.
     */
    private static void allowHostHeaderLikeGatewayDefault() {
        String current = System.getProperty(RESTRICTED_HEADERS_PROPERTY);
        if (!StringUtils.hasText(current)) {
            System.setProperty(RESTRICTED_HEADERS_PROPERTY, "host");
        } else if (!current.contains("host")) {
            System.setProperty(RESTRICTED_HEADERS_PROPERTY, current + ",host");
        }
    }
}
