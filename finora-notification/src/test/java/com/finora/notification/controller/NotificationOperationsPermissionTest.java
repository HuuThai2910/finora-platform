package com.finora.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import com.finora.notification.config.NotificationSecurityConfig;
import com.finora.notification.service.operations.NotificationOperationsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền nhân viên trên {@code /api/v1/notifications/admin/**}: push lỗi và event chờ thuộc chức năng Đối soát &
 * sự cố. Xem cần quyền Xem, đưa push về hàng đợi cần quyền Thao tác; ADMIN luôn qua; đường dẫn chưa gán chức năng chỉ
 * ADMIN. Service là mock nên "qua tầng bảo mật" nghĩa là không bị 401/403.
 */
@WebMvcTest(NotificationOperationsController.class)
@Import({NotificationSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class NotificationOperationsPermissionTest {

    private static final String ADMIN = "/api/v1/notifications/admin";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private NotificationOperationsService operations;
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void reconciliationViewerReadsButCannotRequeue() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.RECONCILIATION.view());

        perform(get(ADMIN + "/push-deliveries"), staff, passesSecurity());
        perform(get(ADMIN + "/deferred-events"), staff, passesSecurity());
        perform(post(ADMIN + "/push-deliveries/7/requeue"), staff, status().isForbidden());

        verify(operations, never()).requeue(any(), anyString());
    }

    @Test
    void reconciliationActorRequeues() throws Exception {
        perform(post(ADMIN + "/push-deliveries/7/requeue"),
                staff(OpsFunction.RECONCILIATION.view(), OpsFunction.RECONCILIATION.action()), passesSecurity());

        verify(operations).requeue(7L, "STAFF-1");
    }

    @Test
    void staffWithoutReconciliationIsDenied() throws Exception {
        perform(get(ADMIN + "/push-deliveries"), staff(), status().isForbidden());
        perform(get(ADMIN + "/deferred-events"), staff(OpsFunction.WITHDRAWAL.view()), status().isForbidden());
    }

    @Test
    void unmappedAdminPathsStayAdminOnly() throws Exception {
        RequestPostProcessor everything = staff(OpsFunction.allPermissions().toArray(String[]::new));

        perform(get(ADMIN + "/unmapped"), everything, status().isForbidden());
        perform(post(ADMIN + "/deferred-events"), everything, status().isForbidden());
        perform(get("/actuator/metrics"), everything, status().isForbidden());
    }

    @Test
    void adminPasses() throws Exception {
        RequestPostProcessor admin = jwt().jwt(token -> token.claim("user_id", "ADMIN-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        perform(get(ADMIN + "/deferred-events"), admin, passesSecurity());
        perform(post(ADMIN + "/push-deliveries/7/requeue"), admin, passesSecurity());
    }

    private void perform(MockHttpServletRequestBuilder request, RequestPostProcessor auth, ResultMatcher expected)
            throws Exception {
        mvc.perform(request.with(auth)).andExpect(expected);
    }

    private static ResultMatcher passesSecurity() {
        return result -> assertThat(result.getResponse().getStatus())
                .as("%s %s", result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isNotIn(401, 403);
    }

    private static RequestPostProcessor staff(String... permissions) {
        GrantedAuthority[] authorities = new GrantedAuthority[permissions.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_STAFF");
        for (int i = 0; i < permissions.length; i++) {
            authorities[i + 1] = new SimpleGrantedAuthority(permissions[i]);
        }
        return jwt().jwt(token -> token.claim("user_id", "STAFF-1")).authorities(authorities);
    }
}
