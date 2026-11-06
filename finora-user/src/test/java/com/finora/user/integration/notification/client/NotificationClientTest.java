package com.finora.user.integration.notification.client;

import com.finora.user.integration.notification.contract.OtpEmailRequest;
import com.finora.user.integration.notification.contract.SuspiciousActivityAlertRequest;
import com.finora.user.integration.notification.contract.WelcomeEmailRequest;
import com.finora.user.util.CircuitBreakerTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gửi email qua endpoint nội bộ của finora-notification bằng RestClient và circuit breaker. */
class NotificationClientTest {

    private static final String BASE = "http://notification.test/api/internal/notifications";

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private MockRestServiceServer server;
    private NotificationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://notification.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NotificationClient(builder.build(),
                CircuitBreakerTestSupport.circuitBreakerFactory(circuitBreakerRegistry));
    }

    @Test
    void postsEachEmailAsJsonToItsInternalPath() {
        server.expect(requestTo(BASE + "/welcome-email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"email\":\"a@finora.test\",\"fullName\":\"Hai\"}", true))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/otp-email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"email\":\"a@finora.test\",\"otp\":\"123456\"}", true))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/suspicious-activity-alert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"email\":\"a@finora.test\",\"ipAddress\":\"10.0.0.1\",\"reason\":\"BRUTE_FORCE\"}", true))
                .andRespond(withSuccess());

        client.sendWelcomeEmail(new WelcomeEmailRequest("a@finora.test", "Hai"));
        client.sendOtpEmail(new OtpEmailRequest("a@finora.test", "123456"));
        client.sendSuspiciousActivityAlert(new SuspiciousActivityAlertRequest("a@finora.test", "10.0.0.1", "BRUTE_FORCE"));

        server.verify();
    }

    @Test
    void serverErrorKeepsRestClientException() {
        server.expect(requestTo(BASE + "/otp-email")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.sendOtpEmail(new OtpEmailRequest("a@finora.test", "123456")))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void openCircuitFailsLikeNotificationDownWithoutCallingIt() {
        circuitBreakerRegistry.circuitBreaker(NotificationClient.CIRCUIT_BREAKER).transitionToForcedOpenState();

        assertThatThrownBy(() -> client.sendOtpEmail(new OtpEmailRequest("a@finora.test", "123456")))
                .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
