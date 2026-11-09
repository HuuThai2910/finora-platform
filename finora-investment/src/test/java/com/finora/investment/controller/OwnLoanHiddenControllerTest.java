package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import com.finora.investment.config.SecurityConfig;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.OrderBookStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Danh sách Gọi vốn và Sàn ẩn khoản vay mà người xem là người vay: controller truyền mã người xem xuống truy vấn,
 * quản trị viên và nhân viên có quyền Xem của màn tương ứng thì không ẩn gì.
 */
@WebMvcTest(controllers = {MarketController.class, OrderBookController.class})
@Import({SecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class OwnLoanHiddenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarketListingService listingService;

    @MockBean
    private OrderBookQueryService queryService;

    @MockBean
    private OrderBookCommandService commandService;

    @MockBean
    private OrderBookStreamService streamService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void fundingListHidesViewersOwnLoans() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isOk());

        verify(listingService).search(isNull(), isNull(), isNull(), isNull(), eq("42"), isNull(), isNull(), any(), eq(0), eq(20));
    }

    @Test
    void advancedFilterBindsAndLegacyAppParametersKeepTheirMeaning() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").param("status", "ALL").param("grade", "A")
                        .param("minRate", "12").param("maxTermMonths", "12")
                        .param("amountMin", "10000000").param("fundedPercentMax", "80").param("grades", "A,B")
                        .param("purposeCode", "education").param("closesFrom", "2026-11-01").param("openedTo", "2026-11-09")
                        .with(user("1", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.finora.investment.dto.request.ListingAdvancedFilter> filter =
                org.mockito.ArgumentCaptor.forClass(com.finora.investment.dto.request.ListingAdvancedFilter.class);
        verify(listingService).search(eq("ALL"), eq("A"), eq(new java.math.BigDecimal("12")), eq(12), isNull(), isNull(),
                isNull(), filter.capture(), eq(0), eq(20));
        org.assertj.core.api.Assertions.assertThat(filter.getValue().amountMin()).isEqualByComparingTo("10000000");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().fundedPercentMax()).isEqualByComparingTo("80");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().grades()).containsExactly("A", "B");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().purposeCode()).isEqualTo("EDUCATION");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().closesFrom()).isEqualTo(java.time.LocalDate.of(2026, 11, 1));
        org.assertj.core.api.Assertions.assertThat(filter.getValue().openedTo()).isEqualTo(java.time.LocalDate.of(2026, 11, 9));
    }

    @Test
    void adminSeesEveryListing() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").param("status", "ALL").with(user("1", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(listingService).search(eq("ALL"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), eq(0), eq(20));
    }

    @Test
    void orderBookListHidesViewersOwnLoans() throws Exception {
        when(queryService.listBooks(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/investments/order-books").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isOk());

        verify(queryService).listBooks(eq("42"), isNull(), any(Pageable.class));
    }

    @Test
    void staffWithFundingViewSeesEveryListing() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").param("status", "ALL")
                        .with(user("7", "ROLE_STAFF", OpsFunction.FUNDING.view())))
                .andExpect(status().isOk());

        verify(listingService).search(eq("ALL"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), eq(0), eq(20));
    }

    @Test
    void staffWithoutFundingViewStillHidesOwnLoans() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        // Quyền Xem Chợ thứ cấp không mở danh sách Gọi vốn: mỗi màn kiểm quyền của chính nó.
        mockMvc.perform(get("/api/v1/market/listings")
                        .with(user("7", "ROLE_STAFF", OpsFunction.SECONDARY_MARKET.view())))
                .andExpect(status().isOk());

        verify(listingService).search(isNull(), isNull(), isNull(), isNull(), eq("7"), isNull(), isNull(), any(), eq(0), eq(20));
    }

    @Test
    void adminSeesEveryOrderBook() throws Exception {
        when(queryService.listBooks(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/investments/order-books").with(user("1", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(queryService).listBooks(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void staffWithSecondaryMarketViewSeesEveryOrderBook() throws Exception {
        when(queryService.listBooks(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/investments/order-books")
                        .with(user("7", "ROLE_STAFF", OpsFunction.SECONDARY_MARKET.view())))
                .andExpect(status().isOk());

        verify(queryService).listBooks(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void staffWithoutSecondaryMarketViewStillHidesOwnLoans() throws Exception {
        when(queryService.listBooks(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/investments/order-books")
                        .with(user("7", "ROLE_STAFF", OpsFunction.FUNDING.view())))
                .andExpect(status().isOk());

        verify(queryService).listBooks(eq("7"), isNull(), any(Pageable.class));
    }

    private static RequestPostProcessor user(String userId, String... authorities) {
        return jwt().jwt(token -> token.claim("user_id", userId))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }
}
