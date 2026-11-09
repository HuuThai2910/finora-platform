package com.finora.loan.integration.profile.client.impl;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.integration.profile.BorrowerIdentityUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Loan đọc họ tên + CCCD đã che của bên vay bằng token service account; lỗi nào cũng là "chưa đọc được", không đoán. */
class UserServiceBorrowerIdentityClientTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final String IDENTITY_URL = "http://user.test/api/v1/internal/users/42/identity";

    private MockRestServiceServer server;
    private ClientCredentialsTokenProvider tokenProvider;
    private UserServiceBorrowerIdentityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user.test");
        server = MockRestServiceServer.bindTo(builder).build();
        tokenProvider = mock(ClientCredentialsTokenProvider.class);
        when(tokenProvider.accessToken()).thenReturn("loan-service-token");
        // Circuit breaker thật: lời gọi HTTP chạy trên luồng TimeLimiter như khi chạy service.
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build());
        client = new UserServiceBorrowerIdentityClient(builder.build(), tokenProvider,
                new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), timeLimiterRegistry, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readsNameAndMaskedIdWithServiceAccountToken() {
        server.expect(requestTo(IDENTITY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer loan-service-token"))
                .andRespond(withSuccess("""
                        {"userId":"42","fullName":" Nguyễn Văn An ","maskedIdNumber":"********1234"}
                        """, MediaType.APPLICATION_JSON));

        BorrowerIdentity identity = client.identity("42");

        server.verify();
        assertThat(identity.fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(identity.maskedIdNumber()).isEqualTo("********1234");
        assertThat(identity.capturedAt()).isEqualTo(NOW);
    }

    @Test
    void borrowerWithoutProfileDataIsReturnedAsEmptyIdentityNotInvented() {
        server.expect(requestTo(IDENTITY_URL))
                .andRespond(withSuccess("""
                        {"userId":"42","fullName":"  ","maskedIdNumber":null}
                        """, MediaType.APPLICATION_JSON));

        BorrowerIdentity identity = client.identity("42");

        assertThat(identity.fullName()).isNull();
        assertThat(identity.maskedIdNumber()).isNull();
    }

    @Test
    void forbiddenMeansTheRoleIsMissingAndIsReportedAsUnavailable() {
        server.expect(requestTo(IDENTITY_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.identity("42"))
                .isInstanceOfSatisfying(BorrowerIdentityUnavailableException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo("USER_IDENTITY_HTTP_403"));
    }

    @Test
    void unauthorizedDropsTheCachedToken() {
        server.expect(requestTo(IDENTITY_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.identity("42")).isInstanceOf(BorrowerIdentityUnavailableException.class);
        verify(tokenProvider).invalidate();
    }

    @Test
    void notFoundIsRetriedLaterRatherThanPrintedAsUnknownBorrower() {
        server.expect(requestTo(IDENTITY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.identity("42"))
                .isInstanceOfSatisfying(BorrowerIdentityUnavailableException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo("USER_IDENTITY_HTTP_404"));
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(IDENTITY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.identity("42"))
                .isInstanceOfSatisfying(BorrowerIdentityUnavailableException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo("USER_IDENTITY_HTTP_500"));
    }

    @Test
    void missingServiceTokenNeverCallsUser() {
        when(tokenProvider.accessToken()).thenThrow(
                new ClientCredentialsTokenProvider.ServiceTokenException("Keycloak lỗi", null));
        server.expect(never(), requestTo(IDENTITY_URL));

        assertThatThrownBy(() -> client.identity("42"))
                .isInstanceOfSatisfying(BorrowerIdentityUnavailableException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo("SERVICE_TOKEN_UNAVAILABLE"));
        server.verify();
    }
}
