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
 * Client HTTP Gateway dùng để gọi service phía sau (B43, D33).
 * <p>
 * Mặc định Gateway MVC dùng JDK {@code HttpClient} ở HTTP/2: với upstream {@code http://} nó gửi kèm
 * {@code Upgrade: h2c}. Tomcat bỏ qua header này, nhưng uvicorn 0.27 (AI) giữ body lại chờ
 * {@code 101 Switching Protocols} nên FastAPI nhận body rỗng và trả 422. Mọi upstream của FINORA là HTTP/1.1
 * trong mạng nội bộ, nên Gateway gọi thẳng HTTP/1.1, không thử nâng cấp. Bean này thay bean
 * {@code gatewayClientHttpRequestFactory} của Gateway MVC ({@code @ConditionalOnMissingBean}).
 */
@Configuration
public class UpstreamHttpClientConfig {

    private static final String RESTRICTED_HEADERS = "jdk.httpclient.allowRestrictedHeaders";

    @Bean
    ClientHttpRequestFactory upstreamClientHttpRequestFactory(GatewayMvcProperties properties) {
        return http11RequestFactory(properties.getHttpClient().getConnectTimeout(),
                properties.getHttpClient().getReadTimeout());
    }

    static JdkClientHttpRequestFactory http11RequestFactory(Duration connectTimeout, Duration readTimeout) {
        allowHostHeader();
        HttpClient.Builder client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
        if (connectTimeout != null) {
            client.connectTimeout(connectTimeout);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client.build());
        if (readTimeout != null) {
            factory.setReadTimeout(readTimeout);
        }
        return factory;
    }

    /** Giữ hành vi của Gateway MVC: JDK client chỉ cho đặt header {@code Host} khi được phép bằng system property. */
    private static void allowHostHeader() {
        String restricted = System.getProperty(RESTRICTED_HEADERS);
        if (!StringUtils.hasText(restricted)) {
            System.setProperty(RESTRICTED_HEADERS, "host");
        } else if (!restricted.contains("host")) {
            System.setProperty(RESTRICTED_HEADERS, restricted + ",host");
        }
    }
}
