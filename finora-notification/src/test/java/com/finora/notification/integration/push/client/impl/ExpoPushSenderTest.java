package com.finora.notification.integration.push.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.integration.push.contract.PushMessage;
import com.finora.notification.integration.push.contract.PushOutcome;
import com.finora.notification.integration.push.contract.PushTicket;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Circuit breaker {@code expo-push} của {@link ExpoPushSender}: circuit mở thì không gọi Expo và trả đúng kết quả của
 * "Expo không với tới được" (lô gửi lỗi tạm thời, receipt rỗng) để worker thử lại theo backoff như cũ. Cấu hình lấy từ
 * {@code application.yml} thật qua auto-config resilience4j, không gọi mạng.
 */
class ExpoPushSenderTest {

    private static final String BASE = "https://exp.example.test/--/api/v2/push";
    private static final String TOKEN = "ExponentPushToken[FAKE-test-token-0001]";

    /** Chỉ dựng phần resilience4j của Spring Boot với {@code application.yml} của Notification. */
    private static final ApplicationContextRunner RESILIENCE4J = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(applicationYml()))
            .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class, Resilience4JAutoConfiguration.class));

    @Test
    void openCircuitTurnsTheBatchIntoRetryableFailuresWithoutCallingExpo() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(ExpoPushSender.CIRCUIT_BREAKER_NAME).transitionToOpenState();
        Harness harness = harness(new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(),
                null));

        List<PushTicket> tickets = harness.sender.send(List.of(message(1), message(2)));

        assertThat(tickets).containsExactly(
                PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_CIRCUIT_OPEN"),
                PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_CIRCUIT_OPEN"));
        harness.server.verify();
    }

    @Test
    void openCircuitReturnsNoReceiptsSoTheWorkerAsksAgainLater() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(ExpoPushSender.CIRCUIT_BREAKER_NAME).transitionToOpenState();
        Harness harness = harness(new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(),
                null));

        assertThat(harness.sender.receipts(List.of("ticket-1", "ticket-2"))).isEmpty();
        harness.server.verify();
    }

    @Test
    void fiveServerErrorsOpenTheCircuitConfiguredInApplicationYml() {
        RESILIENCE4J.run(context -> {
            Harness harness = harness(context.getBean(CircuitBreakerFactory.class));
            harness.server.expect(times(5), requestTo(BASE + "/send")).andRespond(withServerError());

            for (int call = 0; call < 5; call++) {
                assertThat(harness.sender.send(List.of(message(call))))
                        .containsExactly(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_HTTP_500"));
            }
            assertThat(context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker(ExpoPushSender.CIRCUIT_BREAKER_NAME).getState())
                    .isEqualTo(CircuitBreaker.State.OPEN);

            // Lô thứ sáu không tới Expo: mock server chỉ nhận đúng năm request ở trên.
            assertThat(harness.sender.send(List.of(message(6))))
                    .containsExactly(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_CIRCUIT_OPEN"));
            harness.server.verify();
        });
    }

    @Test
    void rateLimitAndOtherClientErrorsDoNotOpenTheCircuit() {
        RESILIENCE4J.run(context -> {
            Harness harness = harness(context.getBean(CircuitBreakerFactory.class));
            harness.server.expect(times(6), requestTo(BASE + "/send"))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

            for (int call = 0; call < 6; call++) {
                assertThat(harness.sender.send(List.of(message(call))))
                        .containsExactly(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_HTTP_429"));
            }
            assertThat(context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker(ExpoPushSender.CIRCUIT_BREAKER_NAME).getState())
                    .isEqualTo(CircuitBreaker.State.CLOSED);
            harness.server.verify();
        });
    }

    @Test
    void timeLimiterFromApplicationYmlOutlastsTheExpoHttpTimeouts() {
        RESILIENCE4J.run(context -> {
            Duration timeout = context.getBean(TimeLimiterRegistry.class)
                    .timeLimiter(ExpoPushSender.CIRCUIT_BREAKER_NAME).getTimeLimiterConfig().getTimeoutDuration();

            // Mặc định connect-timeout 3s + read-timeout 10s: HTTP client phải tự báo timeout trước TimeLimiter.
            assertThat(timeout).isEqualTo(Duration.ofSeconds(15)).isGreaterThan(Duration.ofSeconds(13));
        });
    }

    @Test
    void timeLimiterTimeoutIsTreatedAsUnreachableExpo() {
        TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200)).cancelRunningFuture(true).build());
        Harness harness = harness(new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                timeLimiters, null));
        harness.server.expect(requestTo(BASE + "/send")).andRespond(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-late\"}]}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });

        assertThat(harness.sender.send(List.of(message(1))))
                .containsExactly(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_UNREACHABLE"));
    }

    @Test
    void checkedFailuresFromTheTimeLimiterBecomeRestClientExceptions() {
        assertThat(ExpoPushSender.unchecked(new TimeoutException("expo-push"))).isInstanceOf(RestClientException.class);
        assertThat(ExpoPushSender.unchecked(new InterruptedException())).isInstanceOf(RestClientException.class);
        // Cờ ngắt được đặt lại cho luồng worker (Thread.interrupted() đọc rồi xoá cờ để không ảnh hưởng test khác).
        assertThat(Thread.interrupted()).isTrue();

        IllegalStateException unexpected = new IllegalStateException("lỗi lập trình");
        assertThat(ExpoPushSender.unchecked(unexpected)).isSameAs(unexpected);
    }

    private static PropertySource<?> applicationYml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml")).get(0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static PushMessage message(int index) {
        return new PushMessage(index, TOKEN, "tokenref" + index, "Trả nợ", "Khoản trả nợ của bạn đã được ghi nhận.",
                Map.of("notificationId", "n-" + index, "type", "REPAYMENT_RECORDED", "category", "REPAYMENT"),
                Duration.ofHours(24));
    }

    private static Harness harness(CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(server, new ExpoPushSender(builder.build(), new ObjectMapper(), circuitBreakerFactory));
    }

    private record Harness(MockRestServiceServer server, ExpoPushSender sender) {}
}
