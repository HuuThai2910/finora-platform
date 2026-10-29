package com.finora.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import com.finora.payment.config.PaymentSecurityConfig;
import com.finora.payment.service.disbursement.AdminDisbursementReconciliationService;
import com.finora.payment.service.repayment.AdminRepaymentReconciliationService;
import com.finora.payment.service.withdrawal.AdminWithdrawalService;
import java.util.UUID;
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
 * Phân quyền nhân viên trên {@code /api/v1/admin/**} của Payment theo {@code PaymentSecurityConfig} thật: Rút tiền
 * và Đối soát & sự cố tách quyền Xem/Thao tác, ADMIN luôn qua, đường dẫn quản trị chưa gán chức năng chỉ ADMIN.
 * Service là mock nên "qua tầng bảo mật" nghĩa là không bị 401/403.
 */
@WebMvcTest(controllers = {AdminWithdrawalController.class, AdminDisbursementReconciliationController.class,
        AdminRepaymentReconciliationController.class})
@Import({PaymentSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class AdminPermissionWebTest {

    private static final String ADMIN = "/api/v1/admin";
    private static final String ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminWithdrawalService withdrawalService;
    @MockBean
    private AdminDisbursementReconciliationService disbursementService;
    @MockBean
    private AdminRepaymentReconciliationService repaymentService;
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void withdrawalViewerReadsButCannotRecheck() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.WITHDRAWAL.view());

        perform(get(ADMIN + "/withdrawals"), staff, passesSecurity());
        perform(get(ADMIN + "/withdrawals/" + ID), staff, passesSecurity());
        perform(post(ADMIN + "/withdrawals/" + ID + "/recheck"), staff, status().isForbidden());
    }

    @Test
    void withdrawalActorRechecks() throws Exception {
        perform(post(ADMIN + "/withdrawals/" + ID + "/recheck"),
                staff(OpsFunction.WITHDRAWAL.view(), OpsFunction.WITHDRAWAL.action()), passesSecurity());
    }

    @Test
    void reconciliationViewerReadsBothQueuesButCannotAct() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.RECONCILIATION.view());

        perform(get(ADMIN + "/disbursement-reconciliation"), staff, passesSecurity());
        perform(get(ADMIN + "/disbursement-reconciliation/" + ID), staff, passesSecurity());
        perform(get(ADMIN + "/repayment-reconciliation"), staff, passesSecurity());
        perform(post(ADMIN + "/disbursement-reconciliation/" + ID + "/recheck"), staff, status().isForbidden());
        perform(post(ADMIN + "/repayment-reconciliation/" + ID + "/reconcile"), staff, status().isForbidden());
        perform(post(ADMIN + "/repayment-reconciliation/" + ID + "/repost"), staff, status().isForbidden());
        // Quyền Đối soát không mở màn Rút tiền.
        perform(get(ADMIN + "/withdrawals"), staff, status().isForbidden());
    }

    @Test
    void reconciliationActorRunsRecheckReconcileAndRepost() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.RECONCILIATION.view(), OpsFunction.RECONCILIATION.action());

        perform(post(ADMIN + "/disbursement-reconciliation/" + ID + "/recheck"), staff, passesSecurity());
        perform(post(ADMIN + "/repayment-reconciliation/" + ID + "/reconcile"), staff, passesSecurity());
        perform(post(ADMIN + "/repayment-reconciliation/" + ID + "/repost"), staff, passesSecurity());
    }

    @Test
    void staffWithoutPermissionsIsDenied() throws Exception {
        RequestPostProcessor staff = staff();

        perform(get(ADMIN + "/withdrawals"), staff, status().isForbidden());
        perform(get(ADMIN + "/disbursement-reconciliation"), staff, status().isForbidden());
        perform(get(ADMIN + "/repayment-reconciliation"), staff, status().isForbidden());
    }

    @Test
    void unmappedAdminPathsStayAdminOnly() throws Exception {
        RequestPostProcessor everything = staff(OpsFunction.allPermissions().toArray(String[]::new));

        perform(get(ADMIN + "/unmapped"), everything, status().isForbidden());
        perform(post(ADMIN + "/withdrawals"), everything, status().isForbidden());
        perform(get("/actuator/metrics"), everything, status().isForbidden());
    }

    @Test
    void investorIsDeniedAndAdminPasses() throws Exception {
        RequestPostProcessor investor = jwt().authorities(new SimpleGrantedAuthority("ROLE_INVESTOR"));
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        perform(get(ADMIN + "/withdrawals"), investor, status().isForbidden());
        perform(get(ADMIN + "/withdrawals"), admin, passesSecurity());
        perform(post(ADMIN + "/withdrawals/" + ID + "/recheck"), admin, passesSecurity());
        perform(post(ADMIN + "/disbursement-reconciliation/" + ID + "/recheck"), admin, passesSecurity());
        perform(post(ADMIN + "/repayment-reconciliation/" + ID + "/repost"), admin, passesSecurity());
    }

    @Test
    void anonymousGets401() throws Exception {
        mockMvc.perform(get(ADMIN + "/withdrawals")).andExpect(status().isUnauthorized());
    }

    private void perform(MockHttpServletRequestBuilder request, RequestPostProcessor auth, ResultMatcher expected)
            throws Exception {
        mockMvc.perform(request.with(auth)).andExpect(expected);
    }

    /** Không bị chặn ở tầng bảo mật; service là mock nên phần thân có thể rỗng. */
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
