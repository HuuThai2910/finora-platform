package com.finora.gateway.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

/**
 * Client HTTP Gateway dùng để gọi service phía sau (B43, D33).
 * <p>
 * Mọi upstream của FINORA là HTTP/1.1 trong mạng nội bộ. Client mặc định của Gateway MVC (JDK {@code HttpClient}
 * HTTP/2) gửi kèm {@code Upgrade: h2c}, làm uvicorn 0.27 (AI) giữ body lại và FastAPI trả 422 (B43). Bản sửa đầu
 * dùng JDK {@code HttpClient} ép HTTP/1.1, nhưng trên VM thỉnh thoảng trả 500 với
 * {@code NullPointerException ... Http1Exchange$Http1RequestBodySubscriber ... bodySubscriber is null}: Gateway MVC
 * luôn chép body (kể cả GET rỗng) qua {@code OutputStreamPublisher} của spring-web 6.1.6, và JDK client có lúc nhận
 * tín hiệu body trước khi gắn subscriber. Từ 03/11 dùng Apache HttpClient 5 (luồng ghi đồng bộ, chỉ HTTP/1.1, không
 * nâng cấp h2c). Bean này thay bean {@code gatewayClientHttpRequestFactory} của Gateway MVC
 * ({@code @ConditionalOnMissingBean}).
 * <p>
 * Client chỉ chuyển tiếp nguyên trạng: không tự theo redirect, không giữ cookie, không tự giải nén body (header
 * {@code Content-Encoding} đi thẳng về trình duyệt). Kết nối nhàn rỗi được kiểm lại trước khi dùng và bỏ trước khi
 * Tomcat phía sau đóng (keep-alive 60 giây); lỗi kết nối cũ chỉ được thử lại với phương thức idempotent.
 */
@Configuration
public class UpstreamHttpClientConfig {

    private static final int MAX_CONNECTIONS = 200;
    private static final int MAX_CONNECTIONS_PER_SERVICE = 50;
    private static final TimeValue VALIDATE_AFTER_IDLE = TimeValue.ofSeconds(1);
    private static final TimeValue CONNECTION_TIME_TO_LIVE = TimeValue.ofSeconds(50);
    private static final TimeValue EVICT_IDLE_AFTER = TimeValue.ofSeconds(30);

    @Bean
    ClientHttpRequestFactory upstreamClientHttpRequestFactory(GatewayMvcProperties properties) {
        return http11RequestFactory(properties.getHttpClient().getConnectTimeout(),
                properties.getHttpClient().getReadTimeout());
    }

    static HttpComponentsClientHttpRequestFactory http11RequestFactory(Duration connectTimeout, Duration readTimeout) {
        ConnectionConfig.Builder connection = ConnectionConfig.custom()
                .setValidateAfterInactivity(VALIDATE_AFTER_IDLE)
                .setTimeToLive(CONNECTION_TIME_TO_LIVE);
        if (connectTimeout != null) connection.setConnectTimeout(Timeout.of(connectTimeout));
        if (readTimeout != null) connection.setSocketTimeout(Timeout.of(readTimeout));
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_SERVICE)
                .setDefaultConnectionConfig(connection.build())
                .build();

        RequestConfig.Builder request = RequestConfig.custom().setRedirectsEnabled(false);
        if (readTimeout != null) request.setResponseTimeout(Timeout.of(readTimeout));

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(request.build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .evictIdleConnections(EVICT_IDLE_AFTER)
                .setRetryStrategy(new ConnectionRetryOnly())
                .build();
        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /**
     * Thử lại một lần khi kết nối trong pool đã bị phía sau đóng (chỉ phương thức idempotent, theo mặc định của
     * Apache). Không bao giờ tự gửi lại vì mã trạng thái 429/503: Gateway phải trả nguyên phản hồi của service.
     */
    static final class ConnectionRetryOnly extends DefaultHttpRequestRetryStrategy {

        ConnectionRetryOnly() {
            super(1, TimeValue.ZERO_MILLISECONDS);
        }

        @Override
        public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
            return false;
        }
    }
}
