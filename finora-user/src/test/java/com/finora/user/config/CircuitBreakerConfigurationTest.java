package com.finora.user.config;

import com.finora.user.integration.ai.client.AiEkycClient;
import com.finora.user.integration.ai.client.AiLivenessClient;
import com.finora.user.integration.cic.client.CicMappingClient;
import com.finora.user.integration.loganalytics.LogAnalyticsClient;
import com.finora.user.integration.notification.client.NotificationClient;
import com.finora.user.service.impl.KeycloakAdminServiceImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nạp đúng {@code application.yml} qua auto-config của Resilience4j và Spring Cloud CircuitBreaker (không dựng cả ứng
 * dụng, không đọc {@code .env}) để chắc mỗi tên circuit breaker mà client dùng đều có cấu hình trong file. Thiếu time
 * limiter là rơi về mặc định 1 giây của Spring Cloud CircuitBreaker, cắt cả lời gọi AI bình thường.
 */
class CircuitBreakerConfigurationTest {

    private static final List<String> CIRCUIT_BREAKERS = List.of(
            AiEkycClient.CIRCUIT_BREAKER,
            AiLivenessClient.CIRCUIT_BREAKER,
            NotificationClient.CIRCUIT_BREAKER,
            CicMappingClient.CIRCUIT_BREAKER,
            LogAnalyticsClient.CIRCUIT_BREAKER,
            KeycloakAdminServiceImpl.CIRCUIT_BREAKER);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> loadApplicationYml(context.getEnvironment()))
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class,
                    Resilience4JAutoConfiguration.class));

    @Test
    void everyClientCircuitBreakerHasLoanSettingsFromYml() {
        contextRunner.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            for (String name : CIRCUIT_BREAKERS) {
                assertThat(registry.find(name)).as("circuit breaker %s trong application.yml", name).isPresent();
                CircuitBreakerConfig config = registry.find(name).orElseThrow().getCircuitBreakerConfig();
                assertThat(config.getSlidingWindowSize()).as(name).isEqualTo(10);
                assertThat(config.getMinimumNumberOfCalls()).as(name).isEqualTo(5);
                assertThat(config.getFailureRateThreshold()).as(name).isEqualTo(50f);
                assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).as(name).isEqualTo(30_000L);
                assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).as(name).isEqualTo(2);
                assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).as(name).isTrue();
                assertThat(config.getIgnoreExceptionPredicate().test(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY, "", HttpHeaders.EMPTY, new byte[0], null))).as(name).isTrue();
                assertThat(config.getIgnoreExceptionPredicate().test(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "", HttpHeaders.EMPTY, new byte[0], null))).as(name).isFalse();
            }
        });
    }

    @Test
    void timeLimiterIsReadFromYmlAndAboveEachClientHttpTimeout() {
        contextRunner.run(context -> {
            TimeLimiterRegistry registry = context.getBean(TimeLimiterRegistry.class);
            Binder binder = Binder.get(context.getEnvironment());
            AiServiceProperties ai = binder.bind("finora.ai", AiServiceProperties.class).get();
            ActivityLogProperties activityLog = binder.bind("finora.activity-log", ActivityLogProperties.class).get();
            Duration aiSlowestCall = ai.getConnectTimeout()
                    .plus(max(ai.getDocumentReadTimeout(), ai.getLivenessReadTimeout()));

            Map<String, Duration> expected = Map.of(
                    AiEkycClient.CIRCUIT_BREAKER, Duration.ofSeconds(65),
                    NotificationClient.CIRCUIT_BREAKER, Duration.ofSeconds(75),
                    CicMappingClient.CIRCUIT_BREAKER, Duration.ofSeconds(75),
                    LogAnalyticsClient.CIRCUIT_BREAKER, Duration.ofSeconds(25),
                    KeycloakAdminServiceImpl.CIRCUIT_BREAKER, Duration.ofSeconds(30));
            Map<String, Duration> httpTimeouts = Map.of(
                    AiEkycClient.CIRCUIT_BREAKER, aiSlowestCall,
                    NotificationClient.CIRCUIT_BREAKER,
                    NotificationClient.CONNECT_TIMEOUT.plus(NotificationClient.READ_TIMEOUT),
                    CicMappingClient.CIRCUIT_BREAKER, CicMappingClient.CONNECT_TIMEOUT.plus(CicMappingClient.READ_TIMEOUT),
                    LogAnalyticsClient.CIRCUIT_BREAKER, activityLog.getConnectTimeout().plus(activityLog.getReadTimeout()));

            for (String name : CIRCUIT_BREAKERS) {
                assertThat(registry.find(name)).as("time limiter %s trong application.yml", name).isPresent();
                TimeLimiterConfig config = registry.find(name).orElseThrow().getTimeLimiterConfig();
                assertThat(config.getTimeoutDuration()).as(name).isEqualTo(expected.get(name));
                assertThat(config.shouldCancelRunningFuture()).as(name).isTrue();
                if (httpTimeouts.containsKey(name)) {
                    assertThat(config.getTimeoutDuration()).as("%s phải cao hơn timeout của HTTP client", name)
                            .isGreaterThan(httpTimeouts.get(name));
                }
            }
        });
    }

    @Test
    void callSlowerThanDefaultOneSecondStillSucceedsThroughFactory() {
        contextRunner.run(context -> {
            CircuitBreakerFactory<?, ?> factory = context.getBean(CircuitBreakerFactory.class);

            String result = factory.create(AiEkycClient.CIRCUIT_BREAKER).run(() -> {
                sleep(Duration.ofMillis(1_300));
                return "ok";
            }, failure -> "bị cắt: " + failure.getClass().getSimpleName());

            assertThat(result).isEqualTo("ok");
        });
    }

    private static Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Chỉ dùng giá trị trong file: bỏ biến môi trường và system property của máy chạy test. */
    private static void loadApplicationYml(ConfigurableEnvironment environment) {
        MutablePropertySources propertySources = environment.getPropertySources();
        propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        try {
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                    .forEach(propertySources::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
