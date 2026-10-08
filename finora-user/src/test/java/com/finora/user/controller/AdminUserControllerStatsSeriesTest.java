package com.finora.user.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.finora.common.exception.GlobalExceptionHandler;
import com.finora.user.exception.SecurityExceptionHandler;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.repository.UserStatisticsRepository.RegistrationBucketRow;
import com.finora.user.service.UserProfileService;
import com.finora.user.service.UserStatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm tra biên HTTP của {@code /admin/users/stats/series}: quyền, mã lỗi khoảng sai và hình dạng JSON.
 * <p>
 * Không dựng Spring context (cần Keycloak, Redis, Feign); thay vào đó bọc controller bằng đúng
 * interceptor {@code @PreAuthorize} mà Spring Security dùng, và gắn hai advice lỗi thật của module.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerStatsSeriesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T02:30:00Z"), ZoneOffset.UTC);

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserStatisticsRepository statisticsRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminUserController target = new AdminUserController(
                userProfileService, new UserStatisticsService(statisticsRepository, CLOCK));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        mockMvc = MockMvcBuilders.standaloneSetup(proxyFactory.getProxy())
                .setControllerAdvice(new SecurityExceptionHandler(), new GlobalExceptionHandler())
                // Giống mặc định của Spring Boot: ngày ra JSON dạng "2026-10-01", không phải mảng số.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build()))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminGetsDenseSeries() throws Exception {
        authenticateWith("user:admin:read_all");
        when(statisticsRepository.countRegistrations(any())).thenReturn(List.of(
                new RegistrationBucketRow(LocalDate.of(2026, 10, 3), 4, 3, 1)));
        when(statisticsRepository.countEkycCompletions(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/users/stats/series")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-07")
                        .param("bucket", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-07"))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-10-01"))
                .andExpect(jsonPath("$.points[0].registered").value(0))
                .andExpect(jsonPath("$.points[2].bucketStart").value("2026-10-03"))
                .andExpect(jsonPath("$.points[2].registered").value(4))
                .andExpect(jsonPath("$.points[2].registeredBorrowers").value(3))
                .andExpect(jsonPath("$.points[2].registeredInvestors").value(1))
                .andExpect(jsonPath("$.points[2].ekycVerified").value(0))
                .andExpect(jsonPath("$.points[2].ekycFailed").value(0));
    }

    @Test
    void fromAfterToReturns400WithStatisticsCode() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(get("/api/v1/admin/users/stats/series")
                        .param("from", "2026-10-08")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"));
        verifyNoInteractions(statisticsRepository);
    }

    @Test
    void unknownBucketOrMalformedDateReturns400InsteadOf500() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(get("/api/v1/admin/users/stats/series").param("bucket", "YEAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"));
        mockMvc.perform(get("/api/v1/admin/users/stats/series").param("from", "08/10/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"));
    }

    @Test
    void userWithoutAdminAuthorityGets403() throws Exception {
        authenticateWith("user:profile:read");

        mockMvc.perform(get("/api/v1/admin/users/stats/series"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(statisticsRepository);
    }

    private static void authenticateWith(String authority) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("tester", null, authority);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
