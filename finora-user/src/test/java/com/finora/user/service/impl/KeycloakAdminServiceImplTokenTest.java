package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.config.KeycloakAdminProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;

/**
 * Đăng nhập tài khoản đã bị quản trị viên khóa phải báo đúng là bị khóa, không phải "lỗi hệ thống xác thực"
 * (lỗi gặp khi kiểm thử giao diện ngày 01/11/2026).
 */
class KeycloakAdminServiceImplTokenTest {

    private static final String TOKEN_URL = "http://kc/realms/finora/protocol/openid-connect/token";

    private KeycloakAdminServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setServerUrl("http://kc");
        properties.setRealm("finora");
        properties.setClientId("finora-user-client");
        properties.setClientSecret("secret");
        service = new KeycloakAdminServiceImpl(properties);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
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
}
