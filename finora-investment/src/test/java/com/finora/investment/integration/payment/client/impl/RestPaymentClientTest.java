package com.finora.investment.integration.payment.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.config.ServiceTokenProvider;
import com.finora.investment.integration.payment.PaymentCallException;
import com.finora.investment.integration.payment.contract.PaymentHoldResult;
import com.finora.investment.integration.payment.contract.PaymentTransferResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * R3: lời gọi giữ/nhả/tra cứu khoản giữ phân loại kết quả theo đúng trạng thái và mã lỗi Payment trả. Circuit breaker và
 * time limiter (cấu hình như {@code application.yml}) chỉ thêm kết quả "không kết nối được", không đổi phân loại cũ.
 */
class RestPaymentClientTest {

    private RestClient.Builder builder;
    private ServiceTokenProvider tokens;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private MockRestServiceServer server;
    private RestPaymentClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(ServiceTokenProvider.class);
        when(tokens.accessToken()).thenReturn("service-token");
        // Như resilience4j.circuitbreaker.instances.payment, bỏ tự chuyển sang nửa mở để test khỏi cần scheduler.
        circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
        client = newClient(Duration.ofSeconds(35));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replayedHoldThatWasAlreadyReleasedIsNeverTreatedAsMoneyHeld() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"holdReference":"HOLD-1","amount":2000000.00,"currency":"VND","status":"RELEASED"}
                        """, MediaType.APPLICATION_JSON));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isAlreadyReleased()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getHoldReference()).isEqualTo("HOLD-1");
        server.verify();
    }

    @Test
    void authorizationFailureOnHoldStaysPendingInsteadOfRejectingTheOrder() {
        // Token hoặc quyền lỗi không chứng minh lần gọi trước chưa giữ tiền: để lệnh chờ và đối soát lại.
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCESS_DENIED\",\"message\":\"forbidden\"}"));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-2");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        server.verify();
    }

    @Test
    void insufficientBalanceIsADefinitiveRejection() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_INSUFFICIENT_BALANCE\",\"message\":\"Số dư không đủ\"}"));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-3");

        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("PAYMENT_INSUFFICIENT_BALANCE");
        server.verify();
    }

    @Test
    void holdLookupReturnsEmptyOnlyWhenPaymentNeverHeldMoneyForTheOrder() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_HOLD_NOT_FOUND\",\"message\":\"Không tìm thấy\"}"));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-5"))
                .andRespond(withSuccess("""
                        {"holdReference":"HOLD-5","amount":1000000.00,"currency":"VND","status":"HELD"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-6"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.findHold("IO-4")).isEmpty();
        assertThat(client.findHold("IO-5")).hasValueSatisfying(hold -> {
            assertThat(hold.held()).isTrue();
            assertThat(hold.amount()).isEqualByComparingTo("1000000.00");
        });
        assertThatThrownBy(() -> client.findHold("IO-6"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> assertThat(exception.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void releaseOfAHoldAlreadyInDisbursementStopsRetryingButANetworkErrorDoesNot() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-7/release?orderReference=IO-7"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_HOLD_NOT_RELEASABLE\",\"message\":\"đã capture\"}"));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-8/release?orderReference=IO-8"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.release("HOLD-7", "IO-7"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PAYMENT_HOLD_NOT_RELEASABLE");
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> client.release("HOLD-8", "IO-8"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> assertThat(exception.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void openCircuitAnswersEveryCallAsPaymentUnavailableWithoutCallingPayment() {
        // Mạch mở: không gửi gì tới Payment, mọi thao tác ra đúng kết quả "không kết nối được" (thử lại được) như khi
        // Payment tắt, không bao giờ thành từ chối chắc chắn; lệnh, lần khớp và khoản chuyển thử lại bằng khóa cũ.
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(RestPaymentClient.CIRCUIT_BREAKER_NAME);
        circuitBreaker.transitionToOpenState();

        PaymentHoldResult hold = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-9");
        assertThat(hold.isSuccess()).isFalse();
        assertThat(hold.isRetryable()).isTrue();
        assertThat(hold.getErrorCode()).isEqualTo("PAYMENT_UNAVAILABLE");
        assertThatThrownBy(() -> client.findHold("IO-9"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PAYMENT_UNAVAILABLE");
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getCause()).isInstanceOf(CallNotPermittedException.class);
                });
        assertThatThrownBy(() -> client.release("HOLD-9", "IO-9"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PAYMENT_UNAVAILABLE");
                    assertThat(exception.isRetryable()).isTrue();
                });
        assertUnavailable(client.settleFromHold(
                "HOLD-9", "IO-9", "SELLER-1", new BigDecimal("985000.00"), new BigDecimal("49250.00"), "TRD-9"));
        assertUnavailable(client.transferBetweenInvestors("INV-1", "INV-2", new BigDecimal("150000.00"), "RDR-9"));

        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(5);
        server.verify();
    }

    @Test
    void paymentOutagesOpenTheCircuitButBusinessRejectionsDoNot() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"PAYMENT_INSUFFICIENT_BALANCE\",\"message\":\"Số dư không đủ\"}"));
        }
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(RestPaymentClient.CIRCUIT_BREAKER_NAME);

        // 4xx là câu trả lời nghiệp vụ của Payment: không tính vào tỷ lệ lỗi dù lặp lại.
        for (int i = 0; i < 5; i++) {
            assertThat(client.hold("INV-1", new BigDecimal("2000000.00"), "IO-R" + i).isRetryable()).isFalse();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 5; i++) {
            assertThat(client.hold("INV-1", new BigDecimal("2000000.00"), "IO-D" + i).isRetryable()).isTrue();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        server.verify();

        // Lời gọi sau đó không tới Payment (server không còn expectation) mà vẫn là kết quả chưa chắc chắn.
        assertThat(client.hold("INV-1", new BigDecimal("2000000.00"), "IO-D5").isRetryable()).isTrue();
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    void userJwtIsReadOnTheRequestThreadAndWorkersStillUseTheServiceToken() {
        // Lời gọi HTTP chạy trên thread pool của circuit breaker, nơi không có SecurityContext của request.
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andRespond(withSuccess("""
                        {"holdReference":"HOLD-11","amount":2000000.00,"currency":"VND","status":"HELD"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-11/release?orderReference=IO-11"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andRespond(withSuccess());

        Jwt jwt = Jwt.withTokenValue("user-token").header("alg", "none").subject("INV-1").build();
        // Có danh sách quyền thì token ở trạng thái đã xác thực, như token resource server dựng cho request.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        assertThat(client.hold("INV-1", new BigDecimal("2000000.00"), "IO-11").isSuccess()).isTrue();

        SecurityContextHolder.clearContext();
        client.release("HOLD-11", "IO-11");
        server.verify();
    }

    @Test
    void settlementCutByTheTimeLimiterStaysRetryableInsteadOfFailing() {
        // Hết giờ chờ không chứng minh Payment chưa chuyển tiền: lần khớp chờ thử lại cùng settlementReference.
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-12/settlements"))
                .andRespond(slowly(withSuccess("{\"paymentReference\":\"PAY-12\"}", MediaType.APPLICATION_JSON)));

        PaymentTransferResult result = newClient(Duration.ofMillis(100)).settleFromHold(
                "HOLD-12", "IO-12", "SELLER-1", new BigDecimal("985000.00"), new BigDecimal("49250.00"), "TRD-12");

        assertUnavailable(result);
    }

    @Test
    void interruptWhileWaitingForPaymentStaysRetryableAndKeepsTheInterruptFlag() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/transfers"))
                .andRespond(slowly(withSuccess("{\"paymentReference\":\"PAY-13\"}", MediaType.APPLICATION_JSON)));

        PaymentTransferResult result;
        boolean interrupted;
        Thread.currentThread().interrupt();
        try {
            result = client.transferBetweenInvestors("INV-1", "INV-2", new BigDecimal("150000.00"), "RDR-13");
        } finally {
            interrupted = Thread.interrupted();
        }

        assertUnavailable(result);
        assertThat(interrupted).isTrue();
    }

    /** Client với circuit breaker của {@link #setUp()} và time limiter {@code timeout}; thread pool của Spring Cloud vẫn bật. */
    private RestPaymentClient newClient(Duration timeout) {
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
        Resilience4JCircuitBreakerFactory circuitBreakerFactory = new Resilience4JCircuitBreakerFactory(
                circuitBreakerRegistry, timeLimiterRegistry, null, new Resilience4JConfigurationProperties());
        return new RestPaymentClient(builder, new ObjectMapper(), tokens, circuitBreakerFactory, "http://payment.test/api/v1");
    }

    /** Payment trả lời sau 1 giây (bị ngắt thì thôi): đủ lâu để time limiter hoặc cờ ngắt cắt lời gọi trước. */
    private static ResponseCreator slowly(ResponseCreator response) {
        return request -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Lời gọi Payment bị ngắt");
            }
            return response.createResponse(request);
        };
    }

    private static void assertUnavailable(PaymentTransferResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("PAYMENT_UNAVAILABLE");
    }
}
