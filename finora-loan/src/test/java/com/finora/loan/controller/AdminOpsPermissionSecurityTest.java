package com.finora.loan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.config.SecurityConfig;
import com.finora.loan.service.collection.AdminCollectionService;
import com.finora.loan.service.core.CoreProductSyncService;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import com.finora.loan.service.disbursement.AdminDisbursementSagaService;
import com.finora.loan.service.product.LoanProductService;
import com.finora.loan.service.restructure.LoanRescheduleService;
import com.finora.loan.service.scoring.CreditScoringAssessmentService;
import com.finora.loan.service.servicing.AdminLoanServicingReconciliationService;
import com.finora.loan.service.servicing.AdminRepaymentEventQuarantineService;
import com.finora.loan.service.statistics.AdminLoanStatisticsService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền nhân viên ở tầng URL của {@code SecurityConfig} thật: mỗi API quản trị cần đúng quyền Xem/Thao tác
 * của chức năng mình, ADMIN luôn qua, API quản trị chưa gắn chức năng thì nhân viên bị chặn. Service là mock nên
 * "qua lớp bảo mật" nghĩa là không nhận 401/403; lỗi 400 do thân request rỗng không liên quan đến quyền.
 */
@WebMvcTest(controllers = {
        AdminLoanDecisionController.class,
        AdminCreditScoringController.class,
        AdminLoanProductController.class,
        AdminLoanRescheduleController.class,
        AdminCollectionController.class,
        AdminLoanServicingReconciliationController.class,
        AdminDisbursementSagaController.class,
        AdminRepaymentEventQuarantineController.class,
        AdminLoanStatisticsController.class})
@Import({SecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class AdminOpsPermissionSecurityTest {

    private static final String UUID_1 = "6f1c2c1e-6a7b-4c84-9d0f-2a0e8b7f1a11";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminLoanDecisionService decisionService;
    @MockBean
    private CreditScoringAssessmentService scoringService;
    @MockBean
    private LoanProductService productService;
    @MockBean
    private CoreProductSyncService coreProductSyncService;
    @MockBean
    private LoanRescheduleService rescheduleService;
    @MockBean
    private AdminCollectionService collectionService;
    @MockBean
    private AdminLoanServicingReconciliationService reconciliationService;
    @MockBean
    private AdminDisbursementSagaService sagaService;
    @MockBean
    private AdminRepaymentEventQuarantineService quarantineService;
    @MockBean
    private AdminLoanStatisticsService statisticsService;

    /** Không gọi Keycloak: token giả dựng bằng {@code jwt()} nên decoder không bao giờ được dùng. */
    @MockBean
    private JwtDecoder jwtDecoder;

    /** Một API quản trị cùng chức năng và loại quyền (Xem hay Thao tác) mà nó đòi. */
    record Endpoint(HttpMethod method, String path, OpsFunction function, boolean action) {

        String permission() {
            return action ? function.action() : function.view();
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static List<Endpoint> endpoints() {
        String app = "/api/v1/admin/loan-applications/LA-1";
        String product = "/api/v1/admin/loan-products/1";
        return List.of(
                view(HttpMethod.GET, "/api/v1/admin/loan-applications", OpsFunction.LOAN_APPLICATION),
                view(HttpMethod.GET, app + "/review", OpsFunction.LOAN_APPLICATION),
                view(HttpMethod.GET, app + "/explanation", OpsFunction.LOAN_APPLICATION),
                view(HttpMethod.GET, app + "/assessments", OpsFunction.LOAN_APPLICATION),
                view(HttpMethod.GET, app + "/assessments/5", OpsFunction.LOAN_APPLICATION),
                act(HttpMethod.POST, app + "/approve", OpsFunction.LOAN_APPLICATION),
                act(HttpMethod.POST, app + "/reject", OpsFunction.LOAN_APPLICATION),
                act(HttpMethod.POST, app + "/scoring-retry", OpsFunction.LOAN_APPLICATION),
                view(HttpMethod.GET, "/api/v1/admin/loan-products", OpsFunction.LOAN_PRODUCT),
                view(HttpMethod.GET, product, OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.POST, "/api/v1/admin/loan-products", OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.PUT, product, OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.POST, product + "/activate", OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.POST, product + "/deactivate", OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.POST, product + "/archive", OpsFunction.LOAN_PRODUCT),
                act(HttpMethod.POST, product + "/core-sync", OpsFunction.LOAN_PRODUCT),
                view(HttpMethod.GET, "/api/v1/admin/loan-reschedule-requests", OpsFunction.RESCHEDULE),
                act(HttpMethod.POST, "/api/v1/admin/loan-reschedule-requests/" + UUID_1 + "/approve",
                        OpsFunction.RESCHEDULE),
                act(HttpMethod.POST, "/api/v1/admin/loan-reschedule-requests/" + UUID_1 + "/reject",
                        OpsFunction.RESCHEDULE),
                view(HttpMethod.GET, "/api/v1/admin/collection-cases", OpsFunction.COLLECTION),
                view(HttpMethod.GET, "/api/v1/admin/collection-cases/" + UUID_1 + "/actions", OpsFunction.COLLECTION),
                act(HttpMethod.POST, "/api/v1/admin/collection-cases/" + UUID_1 + "/actions", OpsFunction.COLLECTION),
                view(HttpMethod.GET, "/api/v1/admin/loan-servicing-reconciliation", OpsFunction.RECONCILIATION),
                view(HttpMethod.GET, "/api/v1/admin/loan-servicing-reconciliation/incidents",
                        OpsFunction.RECONCILIATION),
                act(HttpMethod.POST, "/api/v1/admin/loan-servicing-reconciliation/LN-1/reconcile",
                        OpsFunction.RECONCILIATION),
                view(HttpMethod.GET, "/api/v1/admin/disbursement-sagas", OpsFunction.RECONCILIATION),
                act(HttpMethod.POST, "/api/v1/admin/disbursement-sagas/" + UUID_1 + "/requeue",
                        OpsFunction.RECONCILIATION),
                view(HttpMethod.GET, "/api/v1/admin/repayment-event-quarantine", OpsFunction.RECONCILIATION),
                act(HttpMethod.POST, "/api/v1/admin/repayment-event-quarantine/" + UUID_1 + "/replay",
                        OpsFunction.RECONCILIATION),
                view(HttpMethod.GET, "/api/v1/admin/loan-statistics/summary", OpsFunction.DASHBOARD),
                view(HttpMethod.GET, "/api/v1/admin/loan-statistics/series", OpsFunction.DASHBOARD));
    }

    static Stream<Endpoint> actionEndpoints() {
        return endpoints().stream().filter(Endpoint::action);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void staffWithExactPermissionPassesSecurity(Endpoint endpoint) throws Exception {
        int status = perform(endpoint, staff(endpoint.permission()));

        assertThat(status).as(endpoint.toString()).isNotIn(401, 403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actionEndpoints")
    void viewPermissionDoesNotAllowAction(Endpoint endpoint) throws Exception {
        int status = perform(endpoint, staff(endpoint.function().view()));

        assertThat(status).as(endpoint.toString()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void adminPassesEverything(Endpoint endpoint) throws Exception {
        int status = perform(endpoint, jwt().authorities(new SimpleGrantedAuthority(SecurityUtils.ROLE_ADMIN)));

        assertThat(status).as(endpoint.toString()).isNotIn(401, 403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void staffWithoutPermissionsIsForbidden(Endpoint endpoint) throws Exception {
        assertThat(perform(endpoint, staff())).as(endpoint.toString()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void permissionOfAnotherFunctionDoesNotApply(Endpoint endpoint) throws Exception {
        // Có đủ Xem + Thao tác của mọi chức năng khác vẫn không mở được API của chức năng này.
        List<String> others = new ArrayList<>(OpsFunction.allPermissions());
        others.removeIf(permission -> permission.startsWith(OpsFunction.PREFIX + endpoint.function().code() + ":"));

        assertThat(perform(endpoint, staff(others.toArray(String[]::new)))).as(endpoint.toString()).isEqualTo(403);
    }

    @Test
    void viewOnlyStaffReadsButCannotWrite() throws Exception {
        RequestPostProcessor viewer = staff(OpsFunction.LOAN_APPLICATION.view());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/admin/loan-applications").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.POST, "/api/v1/admin/loan-applications/LA-1/approve")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void actionStaffReachesServiceOnPost() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/v1/admin/loan-servicing-reconciliation/LN-1/reconcile")
                        .with(staff(OpsFunction.RECONCILIATION.action())))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(reconciliationService).reconcile("LN-1");
    }

    @Test
    void unlistedAdminApiStaysAdminOnly() throws Exception {
        RequestPostProcessor allPermissions = staff(OpsFunction.allPermissions().toArray(String[]::new));

        for (MockHttpServletRequestBuilder request : List.of(
                request(HttpMethod.GET, "/api/v1/admin/unknown-feature"),
                request(HttpMethod.DELETE, "/api/v1/admin/loan-products/1"),
                request(HttpMethod.PATCH, "/api/v1/admin/loan-applications/LA-1/approve"),
                request(HttpMethod.GET, "/api/v1/admin/loan-applications/LA-1/unknown"),
                request(HttpMethod.GET, "/actuator/metrics"))) {
            mockMvc.perform(request.with(allPermissions)).andExpect(status().isForbidden());
        }
        mockMvc.perform(request(HttpMethod.GET, "/api/v1/admin/unknown-feature")
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityUtils.ROLE_ADMIN))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void anonymousGets401() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/api/v1/admin/loan-applications"))
                .andExpect(status().isUnauthorized());
    }

    private int perform(Endpoint endpoint, RequestPostProcessor auth) throws Exception {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), endpoint.path()).with(auth);
        if (endpoint.method() != HttpMethod.GET) {
            builder.header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    /** Token nhân viên: luôn có ROLE_STAFF, cộng các quyền {@code ops:*} truyền vào. */
    private static RequestPostProcessor staff(String... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(SecurityUtils.ROLE_STAFF));
        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return jwt().authorities(authorities);
    }

    private static Endpoint view(HttpMethod method, String path, OpsFunction function) {
        return new Endpoint(method, path, function, false);
    }

    private static Endpoint act(HttpMethod method, String path, OpsFunction function) {
        return new Endpoint(method, path, function, true);
    }
}
