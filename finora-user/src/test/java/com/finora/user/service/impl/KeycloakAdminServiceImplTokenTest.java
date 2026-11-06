package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.config.KeycloakAdminProperties;
import com.finora.user.util.CircuitBreakerTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.AccessTokenResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Đăng nhập tài khoản đã bị quản trị viên khóa phải báo đúng là bị khóa, không phải "lỗi hệ thống xác thực"
 * (lỗi gặp khi kiểm thử giao diện ngày 01/11/2026).
 */
class KeycloakAdminServiceImplTokenTest {

    private static final String TOKEN_URL = "http://kc/realms/finora/protocol/openid-connect/token";

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private KeycloakAdminServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setServerUrl("http://kc");
        properties.setRealm("finora");
        properties.setClientId("finora-user-client");
        properties.setClientSecret("secret");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new KeycloakAdminServiceImpl(properties, builder.build(),
                CircuitBreakerTestSupport.circuitBreakerFactory(circuitBreakerRegistry));
    }

    @Test
    void disabledAccountIsReportedAsLocked() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled\"}"));

        assertThatThrownBy(() -> service.getUserToken("khoa@finora.test", "Finora@12345"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã bị khóa")
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void otherBadRequestStaysSystemError() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> service.getUserToken("a@finora.test", "x"))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void passwordGrantPostsFormAndReturnsTokens() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "password",
                        "client_id", "finora-user-client",
                        "username", "a@finora.test",
                        "password", "Finora@12345")))
                .andRespond(withSuccess("{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));

        AccessTokenResponse tokens = service.getUserToken("a@finora.test", "Finora@12345");

        assertThat(tokens.getToken()).isEqualTo("at");
        assertThat(tokens.getRefreshToken()).isEqualTo("rt");
        server.verify();
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> service.getUserToken("a@finora.test", "sai"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email hoặc mật khẩu không đúng")
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openCircuitFailsLikeKeycloakDownWithoutCallingIt() {
        circuitBreakerRegistry.circuitBreaker(KeycloakAdminServiceImpl.CIRCUIT_BREAKER).transitionToForcedOpenState();

        assertThatThrownBy(() -> service.getUserToken("a@finora.test", "x"))
                .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
