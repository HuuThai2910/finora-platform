package com.finora.payment.integration.fineract.client;

import com.finora.common.exception.BusinessException;
import com.finora.payment.config.PaymentFineractProperties;
import com.finora.payment.config.ZaloPayProperties;
import com.finora.payment.integration.TestCircuitBreakerFactory;
import com.finora.payment.integration.fineract.FineractQuoteErrors;
import com.finora.payment.integration.fineract.FineractRepaymentException;
import com.finora.payment.integration.fineract.contract.RepaymentCoreCommand;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.finora.payment.integration.fineract.client.FineractRepaymentGateway.POSTING_CIRCUIT_BREAKER;
import static com.finora.payment.integration.fineract.client.FineractRepaymentGateway.QUOTE_CIRCUIT_BREAKER;
import static com.finora.payment.integration.fineract.client.FineractRepaymentGateway.RECONCILIATION_CIRCUIT_BREAKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Circuit breaker của {@link FineractRepaymentGateway} với đúng cấu hình {@code application.yml}, dựng bằng
 * auto-config resilience4j và Spring Cloud CircuitBreaker như khi Payment chạy. Circuit mở thì lệnh không tới Fineract
 * nhưng vẫn ra đúng mã "chưa biết kết quả"/"chưa lấy được" như khi Fineract sập, không bao giờ thành "Fineract từ chối"
 * (dẫn tới hoàn tiền); 4xx không mở circuit nên khoản bị từ chối vẫn được hoàn tiền như trước.
 */
class FineractRepaymentGatewayCircuitBreakerTest {

    private static final String BASE_URL = "http://fineract.test";
    private static final String REPAYMENT_URL = BASE_URL + "/loans/77/transactions?command=repayment";
    private static final String TRANSACTION_JSON = """
            {"principalPortion": 800.00, "interestPortion": 200.00,
             "feeChargesPortion": 0.00, "penaltyChargesPortion": 0.00}
            """;
    private static final String LOAN_JSON = """
            {"summary": {"principalOutstanding": 9200.00, "interestOutstanding": 900.00,
                         "feeChargesOutstanding": 0.00, "penaltyChargesOutstanding": 0.00,
                         "totalOutstanding": 10100.00, "totalOverdue": 0.00},
             "repaymentSchedule": {"periods": [{"dueDate": [2026, 11, 3], "totalOutstandingForPeriod": 1000.00}]}}
            """;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class, Resilience4JAutoConfiguration.class));

    @Test
    void serverErrorsOpenPostingCircuitAndTheNextRepaymentIsNotSentButStillMustBeReconciled() {
        contextRunner.run(context -> {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FineractRepaymentGateway gateway = gateway(context, builder);
            // minimum-number-of-calls 5, failure-rate-threshold 50: năm lần 503 liên tiếp mở circuit.
            server.expect(ExpectedCount.times(5), requestTo(REPAYMENT_URL)).andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            for (int attempt = 1; attempt <= 5; attempt++) {
                assertThatThrownBy(() -> gateway.postRepayment(command()))
                        .isInstanceOfSatisfying(FineractRepaymentException.class, exception ->
                                assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_OUTCOME_UNKNOWN"));
            }
            assertThat(circuitBreakers(context).circuitBreaker(POSTING_CIRCUIT_BREAKER).getState())
                    .isEqualTo(CircuitBreaker.State.OPEN);

            // Lần thứ sáu không được gửi; xử lý như mất kết nối: đối soát theo externalId, không hoàn tiền.
            assertThatThrownBy(() -> gateway.postRepayment(command()))
                    .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                        assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_OUTCOME_UNKNOWN");
                        assertThat(exception.outcomeUnknown()).isTrue();
                    })
                    .hasCauseInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(CallNotPermittedException.class);
            server.verify();
        });
    }

    @Test
    void businessRejectionsDoNotOpenPostingCircuitSoRejectedRepaymentsAreStillRefunded() {
        contextRunner.run(context -> {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FineractRepaymentGateway gateway = gateway(context, builder);
            server.expect(ExpectedCount.times(6), requestTo(REPAYMENT_URL))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));

            for (int attempt = 1; attempt <= 6; attempt++) {
                assertThatThrownBy(() -> gateway.postRepayment(command()))
                        .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                            assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_REJECTED");
                            assertThat(exception.outcomeUnknown()).isFalse();
                        });
            }
            assertThat(circuitBreakers(context).circuitBreaker(POSTING_CIRCUIT_BREAKER).getState())
                    .isEqualTo(CircuitBreaker.State.CLOSED);
            server.verify();
        });
    }

    @Test
    void openQuoteAndReconciliationCircuitsReportUnavailableWithoutBlockingRepaymentPosting() {
        contextRunner.run(context -> {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FineractRepaymentGateway gateway = gateway(context, builder);
            circuitBreakers(context).circuitBreaker(QUOTE_CIRCUIT_BREAKER).transitionToOpenState();
            circuitBreakers(context).circuitBreaker(RECONCILIATION_CIRCUIT_BREAKER).transitionToOpenState();

            assertThatThrownBy(() -> gateway.scheduledRepaymentQuote(77L))
                    .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                        assertThat(exception.code()).isEqualTo("FINERACT_DUE_UNAVAILABLE");
                        assertThat(exception.outcomeUnknown()).isTrue();
                    });
            // Người dùng nhận 503 "thử lại sau" như khi Fineract sập, không phải 502 "hệ thống lõi từ chối".
            assertThatThrownBy(() -> FineractQuoteErrors.read(
                    () -> gateway.earlySettlementQuote(77L, LocalDate.of(2026, 10, 3))))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(exception.getCode()).isEqualTo("FINERACT_REPAYMENT_QUOTE_UNAVAILABLE");
                    });
            // Worker giữ RECONCILIATION_REQUIRED để đọc lại sau, không POST mới và không hoàn tiền.
            assertThatThrownBy(() -> gateway.findPostedRepayment(77L, "FINORA-REPAY-abc"))
                    .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                        assertThat(exception.code()).isEqualTo("FINERACT_RECONCILIATION_UNAVAILABLE");
                        assertThat(exception.outcomeUnknown()).isTrue();
                    });

            // Nhóm ghi trả nợ có circuit riêng nên vẫn gọi Fineract.
            server.expect(requestTo(REPAYMENT_URL))
                    .andRespond(withSuccess("{\"resourceId\":501}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(BASE_URL + "/loans/77/transactions/501"))
                    .andRespond(withSuccess(TRANSACTION_JSON, MediaType.APPLICATION_JSON));
            server.expect(requestTo(BASE_URL + "/loans/77?associations=repaymentSchedule"))
                    .andRespond(withSuccess(LOAN_JSON, MediaType.APPLICATION_JSON));
            assertThat(gateway.postRepayment(command()).transactionId()).isEqualTo(501L);
            server.verify();
        });
    }

    @Test
    void timeLimiterCuttingARepaymentPostLeavesItOutcomeUnknownLikeAReadTimeout() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Fineract trả lời chậm hơn time limiter: POST có thể đã được ghi nên phải đối soát, không được hoàn tiền.
        server.expect(requestTo(REPAYMENT_URL)).andRespond(request -> {
            sleepQuietly(Duration.ofSeconds(5));
            return withSuccess("{\"resourceId\":501}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        FineractRepaymentGateway gateway = new FineractRepaymentGateway(builder.build(), properties(),
                TestCircuitBreakerFactory.create(Duration.ofMillis(200)));

        assertThatThrownBy(() -> gateway.postRepayment(command()))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_OUTCOME_UNKNOWN");
                    assertThat(exception.outcomeUnknown()).isTrue();
                })
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void everyCircuitBreakerUsesLoanSettingsAndATimeLimiterAboveItsHttpTimeouts() {
        contextRunner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            PaymentFineractProperties fineract = binder.bind("finora.payment.fineract", PaymentFineractProperties.class)
                    .get();
            ZaloPayProperties zaloPay = binder.bind("finora.payment.zalopay", ZaloPayProperties.class).get();
            Duration fineractHttp = fineract.connectTimeout().plus(fineract.readTimeout());
            // "zalopay-topup" là ZaloPayTopUpProvider.CIRCUIT_BREAKER (khác gói nên ghi lại tên).
            Map<String, Duration> httpTimeouts = Map.of(
                    QUOTE_CIRCUIT_BREAKER, fineractHttp,
                    POSTING_CIRCUIT_BREAKER, fineractHttp,
                    RECONCILIATION_CIRCUIT_BREAKER, fineractHttp,
                    "zalopay-topup", zaloPay.connectTimeout().plus(zaloPay.readTimeout()));
            TimeLimiterRegistry timeLimiters = context.getBean(TimeLimiterRegistry.class);

            httpTimeouts.forEach((name, httpTimeout) -> {
                CircuitBreakerConfig breaker = circuitBreakers(context).find(name)
                        .orElseThrow(() -> new AssertionError("Thiếu circuit breaker " + name))
                        .getCircuitBreakerConfig();
                assertThat(breaker.getSlidingWindowSize()).as(name).isEqualTo(10);
                assertThat(breaker.getMinimumNumberOfCalls()).as(name).isEqualTo(5);
                assertThat(breaker.getFailureRateThreshold()).as(name).isEqualTo(50f);
                assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).as(name).isEqualTo(30_000L);
                assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState()).as(name).isEqualTo(2);
                assertThat(breaker.isAutomaticTransitionFromOpenToHalfOpenEnabled()).as(name).isTrue();
                assertThat(breaker.getIgnoreExceptionPredicate()
                        .test(new HttpClientErrorException(HttpStatus.BAD_REQUEST))).as(name).isTrue();
                // Spring Cloud mặc định cắt sau 1 giây: thiếu time limiter cùng tên là cắt cả lời gọi bình thường.
                TimeLimiterConfig timeLimiter = timeLimiters.find(name)
                        .orElseThrow(() -> new AssertionError("Thiếu time limiter " + name))
                        .getTimeLimiterConfig();
                assertThat(timeLimiter.getTimeoutDuration()).as(name).isGreaterThan(httpTimeout);
                assertThat(timeLimiter.shouldCancelRunningFuture()).as(name).isTrue();
            });
        });
    }

    private static FineractRepaymentGateway gateway(AssertableApplicationContext context, RestClient.Builder builder) {
        assertThat(context).hasNotFailed();
        return new FineractRepaymentGateway(builder.build(), properties(),
                context.getBean(CircuitBreakerFactory.class));
    }

    private static PaymentFineractProperties properties() {
        return new PaymentFineractProperties(BASE_URL, "default", "mifos", "password", 1L, Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static CircuitBreakerRegistry circuitBreakers(AssertableApplicationContext context) {
        return context.getBean(CircuitBreakerRegistry.class);
    }

    private static RepaymentCoreCommand command() {
        return new RepaymentCoreCommand(77L, "FINORA-REPAY-abc", LocalDate.of(2026, 10, 17), new BigDecimal("1000.00"));
    }
}
