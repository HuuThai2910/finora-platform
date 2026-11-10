package com.finora.investment.integration.user.client.impl;

import com.finora.investment.config.ServiceTokenProvider;
import com.finora.investment.integration.user.client.UserIdentityClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Họ tên theo lô cho danh sách gọi vốn: một lời gọi cho tối đa 100 ID, User lỗi thì báo không đọc được. */
class RestUserIdentityClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestUserIdentityClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
        when(tokens.accessToken()).thenReturn("service-token");
        Resilience4JCircuitBreakerFactory circuitBreakerFactory = new Resilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .ignoreExceptions(HttpClientErrorException.class).build()),
                TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build()),
                null, new Resilience4JConfigurationProperties());
        client = new RestUserIdentityClient(builder, tokens, circuitBreakerFactory, "http://user.test");
    }

    @Test
    void fullNamesAreReadInOneCallWithTheServiceToken() {
        server.expect(requestTo("http://user.test/api/v1/internal/users/full-names?userIds=42&userIds=43"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andRespond(withSuccess("""
                        [{"userId":"42","fullName":"Nguyễn Văn An"},{"userId":"43","fullName":" "}]
                        """, MediaType.APPLICATION_JSON));

        Map<String, String> names = client.fullNames(List.of("42", "43", "42", " "));

        assertThat(names).containsEntry("42", "Nguyễn Văn An").containsKey("43");
        assertThat(names.get("43")).isNull();
        server.verify();
    }

    @Test
    void moreThanOneHundredIdsAreSplitIntoChunks() {
        List<String> ids = new ArrayList<>(IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).toList());
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("userIds=100"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user.test/api/v1/internal/users/full-names?userIds=101"))
                .andRespond(withSuccess("[{\"userId\":\"101\",\"fullName\":\"Lê Thị Mai\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fullNames(ids)).containsOnlyKeys("101");
        server.verify();
    }

    @Test
    void userErrorIsReportedAsUnavailable() {
        server.expect(requestTo("http://user.test/api/v1/internal/users/full-names?userIds=42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fullNames(List.of("42")))
                .isInstanceOf(UserIdentityClient.UserIdentityUnavailableException.class)
                .extracting(exception -> ((UserIdentityClient.UserIdentityUnavailableException) exception).code())
                .isEqualTo("USER_IDENTITY_HTTP_404");
    }

    @Test
    void idsByNameCollectBothIdFormsOfEveryMatch() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/v1/internal/users/ids-by-name");
                    assertThat(java.net.URLDecoder.decode(request.getURI().getRawQuery(),
                            java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("q=Trần An");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andRespond(withSuccess("""
                        [{"userId":"42","keycloakUserId":"6f1c8a2e-0000-4000-8000-000000000042"},
                         {"userId":"43","keycloakUserId":null}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.userIdsByName("Trần An"))
                .containsExactlyInAnyOrder("42", "6f1c8a2e-0000-4000-8000-000000000042", "43");
        server.verify();
    }

    @Test
    void idsByNameReportUserErrorsAsUnavailable() {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/ids-by-name"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.userIdsByName("An"))
                .isInstanceOf(UserIdentityClient.UserIdentityUnavailableException.class)
                .extracting(exception -> ((UserIdentityClient.UserIdentityUnavailableException) exception).code())
                .isEqualTo("USER_IDENTITY_HTTP_403");
    }
}
