package com.finora.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Máy chủ HTTP nhỏ của JDK ghi lại header {@code Upgrade} và body nhận được, để thấy Gateway gửi gì
 * xuống service. uvicorn mất body khi gặp {@code Upgrade: h2c}; ở đây chỉ cần chứng minh client của
 * Gateway không còn xin nâng cấp và body đi nguyên vẹn.
 */
class ProxyHttpClientConfigTest {

    private static final String BODY = "{\"rules\":[{\"ma\":\"CIC\"}]}";

    private HttpServer server;
    private final AtomicReference<String> upgradeHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/ai/config/rules", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void defaultGatewayJdkClientAsksForH2cUpgrade() throws IOException {
        // Ghi lại nguyên nhân gốc: đây đúng là factory Gateway tự tạo khi không có bean của FINORA.
        ClientHttpRequestFactory gatewayDefault = ClientHttpRequestFactories.get(
                JdkClientHttpRequestFactory.class, ClientHttpRequestFactorySettings.DEFAULTS);

        send(gatewayDefault);

        assertThat(upgradeHeader.get()).isEqualTo("h2c");
    }

    @Test
    void http11FactoryForwardsJsonBodyWithoutUpgrade() throws IOException {
        ClientHttpRequestFactory factory =
                new ProxyHttpClientConfig().http11ClientHttpRequestFactory(new GatewayMvcProperties());

        send(factory);

        assertThat(upgradeHeader.get()).isNull();
        assertThat(receivedBody.get()).isEqualTo(BODY);
    }

    private void send(ClientHttpRequestFactory factory) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/ai/config/rules");
        ClientHttpRequest request = factory.createRequest(uri, HttpMethod.PUT);
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        request.getBody().write(BODY.getBytes(StandardCharsets.UTF_8));
        try (ClientHttpResponse response = request.execute()) {
            assertThat(response.getStatusCode().value()).isEqualTo(204);
        }
    }
}
