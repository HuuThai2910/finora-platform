package com.finora.common.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientCredentialsTokenProviderTest {

    private static final String TOKEN_URL = "http://keycloak.test/realms/finora/protocol/openid-connect/token";
    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void cachesTheTokenUntilShortlyBeforeItExpires() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("grant_type", "client_credentials",
                        "client_id", "finora-loan-client")))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"t2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        ClientCredentialsTokenProvider provider = provider(builder, "secret-value");

        assertThat(provider.accessToken()).isEqualTo("t1");
        now.set(NOW.plusSeconds(269));
        assertThat(provider.accessToken()).isEqualTo("t1");
        // Còn dưới 30 giây là làm mới, để token không hết hạn giữa đường tới service nhận.
        now.set(NOW.plusSeconds(271));
        assertThat(provider.accessToken()).isEqualTo("t2");
        server.verify();
    }

    @Test
    void missingSecretFailsWithoutCallingKeycloak() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> provider(builder, " ").accessToken())
                .isInstanceOf(ClientCredentialsTokenProvider.ServiceTokenException.class)
                .hasMessageContaining("finora-loan-client");
        server.verify();
    }

    @Test
    void keycloakErrorIsAServiceTokenExceptionThatNamesOnlyTheClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> provider(builder, "secret-value").accessToken())
                .isInstanceOf(ClientCredentialsTokenProvider.ServiceTokenException.class)
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void invalidateForcesANewToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"t2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        ClientCredentialsTokenProvider provider = provider(builder, "secret-value");

        assertThat(provider.accessToken()).isEqualTo("t1");
        provider.invalidate();
        assertThat(provider.accessToken()).isEqualTo("t2");
    }

    private ClientCredentialsTokenProvider provider(RestClient.Builder builder, String secret) {
        return new ClientCredentialsTokenProvider(builder, clock, TOKEN_URL, "finora-loan-client", secret);
    }
}
