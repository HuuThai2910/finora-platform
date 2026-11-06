package com.finora.gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B43: POST qua Gateway tới upstream không phải Tomcat (AI chạy uvicorn) phải mang đủ body, không kèm h2c. Client chỉ
 * chuyển tiếp nguyên trạng: không theo redirect, không giải nén, GET rỗng lặp lại nhiều lần vẫn ổn (lỗi NPE ngắt quãng
 * của JDK HttpClient trên VM, 03/11).
 */
class UpstreamHttpClientConfigTest {

    private HttpServer upstream;
    private RestClient client;
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
        upstream.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        upstream.createContext("/gzip", exchange -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, bytes.size());
            exchange.getResponseBody().write(bytes.toByteArray());
            exchange.close();
        });
        upstream.start();
        client = RestClient.builder()
                .requestFactory(UpstreamHttpClientConfig.http11RequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort())
                .build();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void postToUpstreamCarriesTheBodyOverHttp11WithoutH2cUpgrade() {
        client.post().uri("/api/v1/ai/credit/explain").contentType(MediaType.APPLICATION_JSON)
                .body("{\"loan_amnt\":10000000}").retrieve().toBodilessEntity();

        assertThat(body.get()).isEqualTo("{\"loan_amnt\":10000000}");
        assertThat(upgradeHeader.get()).isNull();
        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
    }

    @Test
    void repeatedGetsWithEmptyBodyAllSucceed() {
        for (int i = 0; i < 300; i++) {
            ResponseEntity<Void> response = client.get().uri("/api/v1/ai/credit/explain").retrieve().toBodilessEntity();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void passesRedirectAndCompressedBodyThroughUntouched() {
        ResponseEntity<Void> redirect = client.get().uri("/redirect").retrieve().toBodilessEntity();
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("/elsewhere");

        ResponseEntity<byte[]> gzip = client.get().uri("/gzip").retrieve().toEntity(byte[].class);
        assertThat(gzip.getHeaders().getFirst("Content-Encoding")).isEqualTo("gzip");
        assertThat(gzip.getBody()).startsWith((byte) 0x1f, (byte) 0x8b);
    }
}
