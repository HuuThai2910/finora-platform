package com.finora.investment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.investment.config.SecurityConfig;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.OrderBookStreamService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Danh sách Gọi vốn và Sàn ẩn khoản vay mà người xem là người vay: controller truyền mã người xem xuống truy vấn,
 * quản trị viên thì không ẩn gì.
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
        when(listingService.search(any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isOk());

        verify(listingService).search(isNull(), isNull(), isNull(), isNull(), eq("42"), eq(0), eq(20));
    }

    @Test
    void adminSeesEveryListing() throws Exception {
        when(listingService.search(any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(PageResponse.<MarketListingResponse>builder().content(List.of()).build());

        mockMvc.perform(get("/api/v1/market/listings").param("status", "ALL").with(user("1", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(listingService).search(eq("ALL"), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    void orderBookListHidesViewersOwnLoans() throws Exception {
        when(queryService.listBooks(any(), any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/investments/order-books").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isOk());

        verify(queryService).listBooks(eq("42"), any(Pageable.class));
    }

    private static RequestPostProcessor user(String userId, String role) {
        return jwt().jwt(token -> token.claim("user_id", userId)).authorities(new SimpleGrantedAuthority(role));
    }
}
