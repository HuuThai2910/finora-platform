package com.finora.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** B43: POST qua Gateway tới upstream không phải Tomcat (AI chạy uvicorn) phải mang đủ body, không kèm h2c. */
class UpstreamHttpClientConfigTest {

    private HttpServer upstream;
    private final AtomicReference<String> upgradeHeader = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> protocol = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/api/v1/ai/credit/explain", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            protocol.set(exchange.getProtocol());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void postToUpstreamCarriesTheBodyOverHttp11WithoutH2cUpgrade() {
        RestClient client = RestClient.builder()
                .requestFactory(UpstreamHttpClientConfig.http11RequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort())
                .build();

        client.post().uri("/api/v1/ai/credit/explain").contentType(MediaType.APPLICATION_JSON)
                .body("{\"loan_amnt\":10000000}").retrieve().toBodilessEntity();

        assertThat(body.get()).isEqualTo("{\"loan_amnt\":10000000}");
        assertThat(upgradeHeader.get()).isNull();
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
    }

    @Test
    void hostHeaderStaysAllowedLikeGatewayMvcDefault() {
        UpstreamHttpClientConfig.http11RequestFactory(null, null);

        assertThat(System.getProperty("jdk.httpclient.allowRestrictedHeaders")).contains("host");
    }
}
