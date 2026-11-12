package com.finora.investment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.investment.config.OrderBookWebSocketConfig;
import com.finora.investment.config.SecurityConfig;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.exception.InvestmentExceptionHandler;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.orderbook.OrderBookChangedEvent;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.impl.NoOpTransactionManager;
import com.finora.investment.service.orderbook.impl.OrderBookStreamServiceImpl;
import com.finora.investment.service.orderbook.impl.OrderBookStreamTicketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Bắt tay WebSocket thật trên Tomcat nhúng, đi qua đúng chuỗi Spring Security (CORS, permitAll, bỏ qua cookie),
 * nhưng không cần PostgreSQL hay Keycloak: repository và truy vấn sổ là mock, bộ giải mã JWT nhận mọi token và lấy
 * chính chuỗi token làm mã người dùng. Client là {@code java.net.http.WebSocket} của JDK.
 *
 * <p>Bản đầy đủ có DB (đặt lệnh thật rồi nhận ảnh mới) là {@code OrderBookStreamIT}, cần Docker.</p>
 */
@SpringBootTest(classes = OrderBookWebSocketHandshakeTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finora.investment.order-book.websocket.allowed-origin-patterns=https://web.example")
class OrderBookWebSocketHandshakeTest {

    private static final String ALLOWED_ORIGIN = "https://web.example";

    // @Configuration thường, không phải @SpringBootConfiguration: các @WebMvcTest cùng package tìm cấu hình gốc theo
    // package và sẽ nhặt nhầm lớp này thay cho FinoraInvestmentApplication.
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({
            ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import({SecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class,
            InvestmentExceptionHandler.class, OrderBookController.class, OrderBookWebSocketConfig.class,
            OrderBookWebSocketHandler.class, OrderBookHandshakeInterceptor.class,
            OrderBookStreamTicketServiceImpl.class, OrderBookStreamServiceImpl.class})
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        /** Token nào cũng hợp lệ, trừ {@code bad}; token bắt đầu bằng STAFF là nhân viên chưa có quyền gì. */
        @Bean
        JwtDecoder acceptAnyTokenDecoder() {
            return token -> {
                if ("bad".equals(token)) {
                    throw new BadJwtException("token hỏng");
                }
                String role = token.startsWith("STAFF") ? "STAFF" : "INVESTOR";
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(token)
                        .claim("user_id", token)
                        .claim("realm_access", Map.of("roles", List.of(role)))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            };
        }
    }

    @LocalServerPort
    private int port;

    @MockBean
    private MarketListingRepository marketListingRepository;
    @MockBean
    private OrderBookQueryService orderBookQueryService;
    @MockBean
    private OrderBookCommandService orderBookCommandService;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        when(marketListingRepository.existsById(anyLong())).thenReturn(true);
        when(marketListingRepository.existsById(404L)).thenReturn(false);
        when(orderBookQueryService.snapshot(5L)).thenReturn(snapshot(5L, 1), snapshot(5L, 2), snapshot(5L, 3));
    }

    @Test
    @DisplayName("Lấy vé qua REST, bắt tay bằng vé: nhận ảnh đầu tiên, sổ đổi (sau commit) thì nhận ảnh mới")
    void ticketHandshakeThenSnapshots() throws Exception {
        JsonNode ticket = issueTicket(5L, "INV-1");
        assertThat(ticket.get("path").asText()).isEqualTo("/ws/order-books/5");

        Received received = new Received();
        WebSocket socket = connect(ticket.get("path").asText() + "?ticket=" + ticket.get("ticket").asText(),
                null, received);
        try {
            JsonNode initial = received.next();
            assertThat(initial.get("type").asText()).isEqualTo("snapshot");
            assertThat(initial.get("data").get("listingId").asLong()).isEqualTo(5L);
            long firstSequence = initial.get("data").get("sequence").asLong();

            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> publisher.publishEvent(new OrderBookChangedEvent(5L, firstSequence + 1)));

            JsonNode pushed = received.next();
            assertThat(pushed.get("data").get("sequence").asLong()).isGreaterThan(firstSequence);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "xong").join();
        }
    }

    @Test
    @DisplayName("Vé đã dùng, thiếu vé hoặc vé của sổ khác: bắt tay bị từ chối 401")
    void rejectedTickets() throws Exception {
        JsonNode ticket = issueTicket(5L, "INV-2");
        String path = "/ws/order-books/5?ticket=" + ticket.get("ticket").asText();
        WebSocket socket = connect(path, null, new Received());
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "xong").join();

        assertHandshakeStatus(path, null, 401);
        assertHandshakeStatus("/ws/order-books/5", null, 401);

        JsonNode otherBook = issueTicket(5L, "INV-2");
        assertHandshakeStatus("/ws/order-books/6?ticket=" + otherBook.get("ticket").asText(), null, 401);
    }

    @Test
    @DisplayName("Origin lạ bị chặn 403 trước khi kiểm vé (vé vẫn còn); origin trong danh sách thì qua")
    void originCheckedBeforeTicket() throws Exception {
        JsonNode ticket = issueTicket(5L, "INV-3");
        String path = "/ws/order-books/5?ticket=" + ticket.get("ticket").asText();

        assertHandshakeStatus(path, "https://evil.example", 403);

        Received received = new Received();
        WebSocket socket = connect(path, ALLOWED_ORIGIN, received);
        assertThat(received.next().get("type").asText()).isEqualTo("snapshot");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "xong").join();
    }

    @Test
    @DisplayName("Cookie access_token hỏng (dev qua proxy Vite) không chặn bắt tay có vé")
    void staleCookieDoesNotBlockHandshake() throws Exception {
        JsonNode ticket = issueTicket(5L, "INV-4");
        HttpClient client = HttpClient.newHttpClient();
        WebSocket socket = client.newWebSocketBuilder()
                .header("Cookie", "access_token=bad")
                .buildAsync(wsUri("/ws/order-books/5?ticket=" + ticket.get("ticket").asText()), new Received())
                .get(10, TimeUnit.SECONDS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "xong").join();
    }

    @Test
    @DisplayName("API vé: không đăng nhập 401, nhân viên thiếu quyền Xem Chợ chuyển nhượng 403, sổ không có 404")
    void ticketEndpointAuthorization() throws Exception {
        assertThat(postTicket(5L, null).statusCode()).isEqualTo(401);
        assertThat(postTicket(5L, "STAFF-1").statusCode()).isEqualTo(403);
        assertThat(postTicket(404L, "INV-5").statusCode()).isEqualTo(404);
    }

    private JsonNode issueTicket(long listingId, String token) throws Exception {
        HttpResponse<String> response = postTicket(listingId, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> postTicket(long listingId, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/investments/order-books/" + listingId
                                + "/stream-ticket"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WebSocket connect(String pathAndQuery, String origin, Received listener) throws Exception {
        WebSocket.Builder builder = http.newWebSocketBuilder();
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.buildAsync(wsUri(pathAndQuery), listener).get(10, TimeUnit.SECONDS);
    }

    private void assertHandshakeStatus(String pathAndQuery, String origin, int status) {
        assertThatThrownBy(() -> {
            WebSocket.Builder builder = http.newWebSocketBuilder();
            if (origin != null) {
                builder.header("Origin", origin);
            }
            builder.buildAsync(wsUri(pathAndQuery), new Received()).join();
        }).isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        e -> assertThat(e.getResponse().statusCode()).isEqualTo(status));
    }

    private URI wsUri(String pathAndQuery) {
        return URI.create("ws://localhost:" + port + pathAndQuery);
    }

    private static OrderBookSnapshotResponse snapshot(Long listingId, long seq) {
        return new OrderBookSnapshotResponse(listingId, 1L, seq, "B", "15.0000",
                12, "100000.00", null, false, null, null, null, null, null, List.of(), List.of(), List.of(),
                "Vốn kinh doanh", "BUSINESS");
    }

    /** Gom tin văn bản (có thể đến thành nhiều mảnh) thành từng tin JSON. */
    private final class Received implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next() throws Exception {
            String message = messages.poll(10, TimeUnit.SECONDS);
            assertThat(message).as("không nhận được tin trong 10 giây").isNotNull();
            return objectMapper.readTree(message);
        }
    }
}
