package com.finora.investment.service.orderbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.NoteIssuanceService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Stream SSE qua HTTP thật, đi qua đúng chuỗi Spring Security: mở stream nhận ảnh đầu tiên, đặt
 * lệnh qua REST, và nhận ảnh mới có lệnh đó mà không phải hỏi lại.
 *
 * <p>Thay bộ giải mã JWT bằng bản chấp nhận mọi token và lấy chính chuỗi token làm mã người dùng:
 * bài này kiểm luồng đẩy dữ liệu, còn kiểm chữ ký token là việc của Keycloak và Spring Security.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000"
})
@Testcontainers
class OrderBookStreamIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @TestConfiguration
    static class AcceptAnyToken {
        @Bean
        @Primary
        JwtDecoder acceptAnyTokenDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(token)
                    .claim("user_id", token)
                    .claim("realm_access", Map.of("roles", List.of("INVESTOR")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MarketListingService listingService;
    @Autowired
    private FundingService fundingService;
    @Autowired
    private NoteIssuanceService noteIssuanceService;
    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("Mở stream nhận ảnh hiện tại; đặt lệnh bán qua REST thì stream đẩy ảnh mới có mức giá đó")
    void pushesSnapshotAfterOrder() throws Exception {
        Long listingId = issuedListing();

        BlockingQueue<JsonNode> snapshots = new LinkedBlockingQueue<>();
        HttpRequest streamRequest = HttpRequest.newBuilder(uri("/api/v1/investments/order-books/" + listingId + "/stream"))
                .header("Authorization", "Bearer OBS-WATCHER")
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var streamFuture = http.sendAsync(streamRequest, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    try (Stream<String> lines = response.body()) {
                        lines.filter(line -> line.startsWith("data:"))
                                .forEach(line -> snapshots.add(readJson(line.substring(5))));
                    }
                });

        try {
            JsonNode initial = snapshots.poll(10, TimeUnit.SECONDS);
            assertThat(initial).isNotNull();
            assertThat(initial.get("listingId").asLong()).isEqualTo(listingId);
            assertThat(initial.get("asks")).isEmpty();
            // Ảnh công khai không lộ ai đặt lệnh.
            assertThat(initial.toString()).doesNotContain("OBS-SELLER");

            HttpResponse<String> placed = http.send(HttpRequest.newBuilder(
                            uri("/api/v1/investments/order-books/" + listingId + "/orders"))
                    .header("Authorization", "Bearer OBS-SELLER")
                    .header("Idempotency-Key", "stream-ask-1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"side\":\"ASK\",\"pricePercent\":97.0,\"quantity\":2}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(placed.statusCode()).isEqualTo(201);

            JsonNode pushed = snapshots.poll(10, TimeUnit.SECONDS);
            assertThat(pushed).isNotNull();
            assertThat(pushed.get("sequence").asLong()).isGreaterThan(initial.get("sequence").asLong());
            assertThat(pushed.get("bestAskPercent").asText()).isEqualTo("97.0");
            assertThat(pushed.get("asks").get(0).get("quantity").asLong()).isEqualTo(2);
            assertThat(pushed.toString()).doesNotContain("OBS-SELLER");
        } finally {
            streamFuture.cancel(true);
        }
    }

    @Test
    @DisplayName("Không có token thì không mở được stream")
    void streamRequiresAuthentication() throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/api/v1/investments/order-books/1/stream"))
                .timeout(Duration.ofSeconds(10))
                .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private Long issuedListing() {
        actAs("ADMIN-OBS", "ROLE_ADMIN");
        MarketListingResponse listing = listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(77001L)
                .applicationNumber("LA-OBS-77001")
                .listingVersion(1)
                .fundingRound(1)
                .termsVersion("LOAN_TERMS_V1")
                .termsHash("a".repeat(64))
                .productCode("SP-01")
                .purpose("Vốn kinh doanh")
                .region("TP.HCM")
                .creditGrade("B")
                .creditScore(700)
                .targetAmount(new BigDecimal("3000000.00"))
                .annualInterestRate(new BigDecimal("15.0000"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .build());
        actAs("OBS-SELLER", "ROLE_INVESTOR");
        fundingService.placeOrder(listing.listingId(), "obs-fund",
                new PlaceOrderRequest(new BigDecimal("3000000.00")));
        noteIssuanceService.finalizeCommitments(listing.listingId());
        noteIssuanceService.activateNotes(listing.listingId());
        SecurityContextHolder.clearContext();
        return listing.listingId();
    }

    private void actAs(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", userId)
                .claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), userId));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
