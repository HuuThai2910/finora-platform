package com.finora.investment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.investment.config.SecurityConfig;
import com.finora.investment.repository.InvestmentStatisticsRepository;
import com.finora.investment.repository.InvestmentStatisticsRepository.TradeBucketRow;
import com.finora.investment.service.statistics.InvestmentStatisticsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Biên HTTP của {@code /investments/admin/statistics}: phân quyền theo URL của {@code SecurityConfig} thật,
 * mã lỗi khoảng sai qua {@code GlobalExceptionHandler} và hình dạng JSON (tiền là số, ngày là chuỗi).
 */
@WebMvcTest(controllers = InvestmentStatisticsAdminController.class)
@Import({SecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class,
        InvestmentStatisticsService.class, InvestmentStatisticsAdminControllerTest.FixedClock.class})
class InvestmentStatisticsAdminControllerTest {

    private static final String SERIES = "/api/v1/investments/admin/statistics/series";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvestmentStatisticsRepository repository;

    /** Không gọi Keycloak: token giả dựng bằng {@code jwt()} nên decoder không bao giờ được dùng. */
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void adminGetsDenseSeriesWithNumericMoney() throws Exception {
        when(repository.commitmentsByBucket(any())).thenReturn(List.of());
        when(repository.fullyFundedListingsByBucket(any())).thenReturn(List.of());
        when(repository.autoInvestByBucket(any())).thenReturn(List.of());
        // 3 lần khớp, mỗi lần 1 Note giá 985; 1 lần là Note nợ xấu nên nhóm performing còn 2 lần, 2 Note.
        when(repository.tradesByBucket(any())).thenReturn(List.of(new TradeBucketRow(
                LocalDate.of(2026, 10, 3), 3, new BigDecimal("2955000"), new BigDecimal("14775"), 2955, 3,
                2, 1970, 2)));

        mockMvc.perform(get(SERIES).param("from", "2026-10-01").param("to", "2026-10-03").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-03"))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-10-01"))
                .andExpect(jsonPath("$.points[0].averagePricePermille").isEmpty())
                .andExpect(jsonPath("$.points[0].tradedQuantity").value(0))
                .andExpect(jsonPath("$.points[0].performingTrades").value(0))
                .andExpect(jsonPath("$.points[0].performingQuantity").value(0))
                .andExpect(jsonPath("$.points[0].performingAveragePricePermille").isEmpty())
                .andExpect(jsonPath("$.points[0].committedAmount").value(0.0))
                .andExpect(jsonPath("$.points[2].trades").value(3))
                .andExpect(jsonPath("$.points[2].tradedAmount").value(2955000.0))
                .andExpect(jsonPath("$.points[2].platformFee").value(14775.0))
                .andExpect(jsonPath("$.points[2].averagePricePermille").value(985))
                .andExpect(jsonPath("$.points[2].tradedQuantity").value(3))
                .andExpect(jsonPath("$.points[2].performingTrades").value(2))
                .andExpect(jsonPath("$.points[2].performingQuantity").value(2))
                .andExpect(jsonPath("$.points[2].performingAveragePricePermille").value(985));
    }

    @Test
    void fromAfterToReturns400WithStatisticsCode() throws Exception {
        mockMvc.perform(get(SERIES).param("from", "2026-10-08").param("to", "2026-10-01").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"));
        verifyNoInteractions(repository);
    }

    @Test
    void unknownBucketReturns400InsteadOf500() throws Exception {
        mockMvc.perform(get(SERIES).param("bucket", "YEAR").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"));
    }

    @Test
    void investorGets403() throws Exception {
        mockMvc.perform(get(SERIES).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVESTOR"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/investments/admin/statistics/summary")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVESTOR"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void anonymousGets401() throws Exception {
        mockMvc.perform(get("/api/v1/investments/admin/statistics/summary"))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock statisticsTestClock() {
            return Clock.fixed(Instant.parse("2026-10-08T02:30:00Z"), ZoneOffset.UTC);
        }
    }
}
