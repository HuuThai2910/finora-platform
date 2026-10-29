package com.finora.investment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import com.finora.investment.config.SecurityConfig;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.NoteIssuanceService;
import com.finora.investment.service.impl.OrderIncidentService;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import com.finora.investment.service.statistics.InvestmentStatisticsService;
import com.finora.investment.service.transferdocument.TransferDocumentAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền nhân viên trên {@code /investments/admin/**} theo {@code SecurityConfig} thật: mỗi đường dẫn kiểm quyền
 * Xem/Thao tác của đúng chức năng, ADMIN luôn qua, đường dẫn chưa gán chức năng vẫn chỉ ADMIN. Service đều là mock
 * nên "qua tầng bảo mật" nghĩa là không bị 401/403; nội dung trả về không phải việc của test này.
 */
@WebMvcTest(controllers = {InvestmentAdminController.class, OrderBookAdminController.class,
        TransferDocumentAdminController.class, InvestmentStatisticsAdminController.class})
@Import({SecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class InvestmentAdminPermissionTest {

    private static final String ADMIN = "/api/v1/investments/admin";
    private static final String SETTINGS_BODY =
            "{\"noteDenomination\":100000,\"minInvestmentAmount\":100000,\"fundingDays\":14}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarketListingService listingService;
    @MockBean
    private NoteIssuanceService noteIssuanceService;
    @MockBean
    private FundingSettingsService settingsService;
    @MockBean
    private OrderIncidentService orderIncidentService;
    @MockBean
    private OrderBookAdminService orderBookAdminService;
    @MockBean
    private TransferDocumentAdminService transferDocumentAdminService;
    @MockBean
    private InvestmentStatisticsService statisticsService;
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void fundingViewerReadsButCannotAct() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.FUNDING.view());

        perform(get(ADMIN + "/listings/1/investors"), staff, passesSecurity());
        perform(get(ADMIN + "/commitments/1/notes"), staff, passesSecurity());
        perform(post(ADMIN + "/listings/1/finalize"), staff, status().isForbidden());
        perform(post(ADMIN + "/listings/1/activate-notes"), staff, status().isForbidden());
        perform(post(ADMIN + "/listings/close-expired"), staff, status().isForbidden());
    }

    @Test
    void fundingActorRunsFixOperations() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.FUNDING.view(), OpsFunction.FUNDING.action());

        perform(post(ADMIN + "/listings/1/finalize"), staff, passesSecurity());
        perform(post(ADMIN + "/listings/1/activate-notes"), staff, passesSecurity());
        perform(post(ADMIN + "/listings/close-expired"), staff, passesSecurity());
    }

    @Test
    void fundingSettingsSplitViewAndUpdate() throws Exception {
        perform(get(ADMIN + "/settings"), staff(OpsFunction.FUNDING_SETTINGS.view()), passesSecurity());
        perform(put(ADMIN + "/settings").contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY),
                staff(OpsFunction.FUNDING_SETTINGS.view()), status().isForbidden());
        // Quyền Gọi vốn không kéo theo quyền cấu hình gọi vốn.
        perform(get(ADMIN + "/settings"), staff(OpsFunction.FUNDING.view(), OpsFunction.FUNDING.action()),
                status().isForbidden());
    }

    @Test
    void reconciliationViewerSeesIncidentsButCannotRetry() throws Exception {
        perform(get(ADMIN + "/order-incidents"), staff(OpsFunction.RECONCILIATION.view()), passesSecurity());
        perform(post(ADMIN + "/order-incidents/ORD-1/retry"), staff(OpsFunction.RECONCILIATION.view()),
                status().isForbidden());
    }

    @Test
    void secondaryMarketViewerReadsBooksAndDocuments() throws Exception {
        RequestPostProcessor staff = staff(OpsFunction.SECONDARY_MARKET.view());

        perform(get(ADMIN + "/order-books/summary"), staff, passesSecurity());
        perform(get(ADMIN + "/order-books/trades"), staff, passesSecurity());
        perform(get(ADMIN + "/transfer-documents"), staff, passesSecurity());
        perform(get(ADMIN + "/transfer-documents/TRD-1"), staff, passesSecurity());
        perform(get(ADMIN + "/transfer-documents/TRD-1/pdf"), staff, passesSecurity());
        perform(post(ADMIN + "/transfer-documents/TRD-1/retry"), staff, status().isForbidden());
        perform(post(ADMIN + "/transfer-documents/TRD-1/retry"),
                staff(OpsFunction.SECONDARY_MARKET.view(), OpsFunction.SECONDARY_MARKET.action()), passesSecurity());
    }

    @Test
    void dashboardViewerReadsStatistics() throws Exception {
        perform(get(ADMIN + "/statistics/summary"), staff(OpsFunction.DASHBOARD.view()), passesSecurity());
        perform(get(ADMIN + "/statistics/summary"), staff(OpsFunction.FUNDING.view()), status().isForbidden());
    }

    @Test
    void staffWithoutPermissionsIsDeniedEverywhere() throws Exception {
        RequestPostProcessor staff = staff();

        perform(get(ADMIN + "/listings/1/investors"), staff, status().isForbidden());
        perform(get(ADMIN + "/settings"), staff, status().isForbidden());
        perform(get(ADMIN + "/order-incidents"), staff, status().isForbidden());
        perform(get(ADMIN + "/order-books/summary"), staff, status().isForbidden());
        perform(get(ADMIN + "/transfer-documents"), staff, status().isForbidden());
        perform(get(ADMIN + "/statistics/summary"), staff, status().isForbidden());
    }

    @Test
    void unmappedAdminPathsStayAdminOnly() throws Exception {
        RequestPostProcessor everything = staff(OpsFunction.allPermissions().toArray(String[]::new));

        // Đường dẫn quản trị chưa gán chức năng và actuator: nhân viên đủ quyền vẫn bị chặn.
        perform(get(ADMIN + "/unmapped"), everything, status().isForbidden());
        perform(post(ADMIN + "/settings"), everything, status().isForbidden());
        perform(get("/actuator/metrics"), everything, status().isForbidden());
    }

    @Test
    void adminPassesEveryMappedPath() throws Exception {
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        perform(get(ADMIN + "/listings/1/investors"), admin, passesSecurity());
        perform(post(ADMIN + "/listings/close-expired"), admin, passesSecurity());
        perform(put(ADMIN + "/settings").contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY), admin,
                passesSecurity());
        perform(post(ADMIN + "/order-incidents/ORD-1/retry"), admin, passesSecurity());
        perform(post(ADMIN + "/transfer-documents/TRD-1/retry"), admin, passesSecurity());
        perform(get(ADMIN + "/statistics/summary"), admin, passesSecurity());
    }

    @Test
    void settingsActorPassesUrlRule() throws Exception {
        perform(put(ADMIN + "/settings").contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY),
                staff(OpsFunction.FUNDING_SETTINGS.view(), OpsFunction.FUNDING_SETTINGS.action()), passesSecurity());
    }

    private void perform(MockHttpServletRequestBuilder request, RequestPostProcessor auth, ResultMatcher expected)
            throws Exception {
        mockMvc.perform(request.with(auth)).andExpect(expected);
    }

    /** Không bị chặn ở tầng bảo mật; service là mock nên phần thân có thể rỗng hoặc lỗi 500. */
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
