package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.service.UserProfileService;
import com.finora.user.service.UserStatisticsService;
import com.finora.user.service.impl.UserStatisticsServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quyền ở màn Khách hàng: nhân viên xem/khoá theo {@code ops:customer:*}, thống kê mở thêm cho
 * {@code ops:dashboard:view}, authority cũ {@code user:admin:*} của service account vẫn chạy, gán vai trò chỉ ADMIN.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerPermissionTest {

    private static final String BASE = "/api/v1/admin/users";
    private static final String LOCK_BODY = "{\"reason\":\"Nghi giả mạo\"}";

    @Mock private UserProfileService userProfileService;
    @Mock private UserStatisticsRepository statisticsRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new AdminUserController(userProfileService,
                new UserStatisticsServiceImpl(statisticsRepository, Clock.systemUTC())));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerViewCanListAndReadDetail() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:customer:view");
        when(userProfileService.searchUsers(any(), any())).thenReturn(PageResponse.<UserProfileResponse>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true).build());

        mockMvc.perform(get(BASE)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/42")).andExpect(status().isOk());
        verify(userProfileService).getUserById(42L);
    }

    @Test
    void scopeIsPassedToServiceForServerSidePaging() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(userProfileService.searchUsers(any(), any())).thenReturn(PageResponse.<UserProfileResponse>builder()
                .content(List.of()).page(0).size(10).totalElements(0).totalPages(0).last(true).build());

        mockMvc.perform(get(BASE).param("scope", "STAFF").param("role", "ADMIN").param("size", "10"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.finora.user.dto.request.UserSearchCriteria> criteria =
                org.mockito.ArgumentCaptor.forClass(com.finora.user.dto.request.UserSearchCriteria.class);
        verify(userProfileService).searchUsers(criteria.capture(), any());
        org.assertj.core.api.Assertions.assertThat(criteria.getValue().scope()).isEqualTo("STAFF");
        org.assertj.core.api.Assertions.assertThat(criteria.getValue().role()).isEqualTo("ADMIN");
    }

    @Test
    void advancedFilterParametersBind() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(userProfileService.searchUsers(any(), any())).thenReturn(PageResponse.<UserProfileResponse>builder()
                .content(List.of()).page(0).size(10).totalElements(0).totalPages(0).last(true).build());

        mockMvc.perform(get(BASE).param("createdFrom", "2026-11-01").param("createdTo", "2026-11-09")
                        .param("locked", "true").param("ageMin", "18").param("ageMax", "30")
                        .param("gender", "FEMALE").param("lastLoginFrom", "2026-11-08"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.finora.user.dto.request.UserSearchCriteria> criteria =
                org.mockito.ArgumentCaptor.forClass(com.finora.user.dto.request.UserSearchCriteria.class);
        verify(userProfileService).searchUsers(criteria.capture(), any());
        var filter = criteria.getValue().filter();
        org.assertj.core.api.Assertions.assertThat(filter.createdFrom()).isEqualTo(java.time.LocalDate.of(2026, 11, 1));
        org.assertj.core.api.Assertions.assertThat(filter.locked()).isTrue();
        org.assertj.core.api.Assertions.assertThat(filter.ageMax()).isEqualTo(30);
        org.assertj.core.api.Assertions.assertThat(filter.gender())
                .isEqualTo(com.finora.common.enums.user.Gender.FEMALE);
        org.assertj.core.api.Assertions.assertThat(filter.lastLoginFrom()).isEqualTo(java.time.LocalDate.of(2026, 11, 8));
    }

    @Test
    void serviceAccountReadAllStillWorks() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(get(BASE + "/42")).andExpect(status().isOk());
    }

    @Test
    void dashboardViewCanReadStatsButNotCustomers() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:dashboard:view");
        when(userProfileService.getUserStats()).thenReturn(UserStatsResponse.builder()
                .total(0).byRole(Map.of()).byEkycStatus(Map.of()).build());

        mockMvc.perform(get(BASE + "/stats")).andExpect(status().isOk());
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/42")).andExpect(status().isForbidden());
    }

    @Test
    void lockNeedsCustomerAction() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:customer:view");
        mockMvc.perform(post(BASE + "/42/lock").contentType(MediaType.APPLICATION_JSON).content(LOCK_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/42/unlock")).andExpect(status().isForbidden());

        authenticateWith("ROLE_STAFF", "ops:customer:view", "ops:customer:action");
        mockMvc.perform(post(BASE + "/42/lock").contentType(MediaType.APPLICATION_JSON).content(LOCK_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/42/unlock")).andExpect(status().isOk());
        verify(userProfileService).lockUser(42L, "Nghi giả mạo", USER_ID);
        verify(userProfileService).unlockUser(42L);
    }

    @Test
    void assignRoleIsAdminOnlyEvenWithLegacyAuthorityOrAllOpsPermissions() throws Exception {
        List<String> authorities = new ArrayList<>(OpsFunction.allPermissions());
        authorities.add("ROLE_STAFF");
        authorities.add("user:admin:assign_role");
        authenticateWith(authorities.toArray(String[]::new));

        mockMvc.perform(post(BASE + "/42/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userProfileService);
    }

    @Test
    void adminAssignsStaffRoleWithCode() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE + "/42/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STAFF\",\"staffRoleCode\":\"STAFF_VAN_HANH\"}"))
                .andExpect(status().isOk());
        verify(userProfileService).assignRole(eq(42L), eq("STAFF"), eq("STAFF_VAN_HANH"), eq(USER_ID));
    }

    @Test
    void legacyQueryParamStillAccepted() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE + "/42/roles").param("role", "INVESTOR")).andExpect(status().isOk());
        verify(userProfileService).assignRole(42L, "INVESTOR", null, USER_ID);
    }
}
