package com.finora.user.controller;

import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.StaffRoleService;
import com.finora.user.service.impl.StaffRoleServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Màn Phân quyền: chỉ ADMIN cấu hình được vai trò (nhân viên có đủ mọi quyền ops:* vẫn bị chặn), kiểm danh mục
 * quyền, tự thêm quyền Xem đi kèm Thao tác, chặn xoá vai trò đang dùng/vai trò có sẵn.
 */
@ExtendWith(MockitoExtension.class)
class AdminStaffRoleControllerTest {

    private static final String BASE = "/api/v1/admin/staff-roles";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC);

    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private UserProfileRepository userProfileRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new AdminStaffRoleController(
                new StaffRoleServiceImpl(keycloakAdminService, userProfileRepository, CLOCK)));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permissionCatalogKeepsEnumOrder() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(get(BASE + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(OpsFunction.values().length))
                .andExpect(jsonPath("$[0].code").value("dashboard"))
                .andExpect(jsonPath("$[0].view").value("ops:dashboard:view"))
                .andExpect(jsonPath("$[0].action").doesNotExist())
                .andExpect(jsonPath("$[1].code").value("loan-application"))
                .andExpect(jsonPath("$[1].label").value("Hồ sơ vay"))
                .andExpect(jsonPath("$[1].group").value("Cho vay"))
                .andExpect(jsonPath("$[1].action").value("ops:loan-application:action"));
    }

    @Test
    void listReturnsBuiltInFirstWithMemberCount() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(keycloakAdminService.listStaffRoles()).thenReturn(List.of(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "An thu hồi nợ", null,
                        List.of("ops:collection:view", "ops:collection:action")),
                new StaffRoleDefinition(StaffRoleService.BUILT_IN_CODE, "Nhân viên vận hành", "mặc định",
                        OpsFunction.defaultStaffPermissions())));
        List<Object[]> counts = new ArrayList<>();
        counts.add(new Object[] {"STAFF_THU_HOI_NO", 3L});
        when(userProfileRepository.countGroupedByStaffRoleCode()).thenReturn(counts);

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("STAFF_VAN_HANH"))
                .andExpect(jsonPath("$[0].builtIn").value(true))
                .andExpect(jsonPath("$[0].memberCount").value(0))
                .andExpect(jsonPath("$[1].code").value("STAFF_THU_HOI_NO"))
                .andExpect(jsonPath("$[1].name").value("An thu hồi nợ"))
                .andExpect(jsonPath("$[1].permissions[1]").value("ops:collection:action"))
                .andExpect(jsonPath("$[1].memberCount").value(3))
                .andExpect(jsonPath("$[1].builtIn").value(false));
    }

    @Test
    void createBuildsCodeFromVietnameseNameAndAddsViewForAction() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Nhân viên đối soát","description":" ","permissions":
                          ["ops:withdrawal:action","ops:dashboard:view","ops:withdrawal:action"]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("STAFF_NHAN_VIEN_DOI_SOAT"))
                .andExpect(jsonPath("$.name").value("Nhân viên đối soát"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.permissions.length()").value(3))
                .andExpect(jsonPath("$.permissions[0]").value("ops:dashboard:view"))
                .andExpect(jsonPath("$.permissions[1]").value("ops:withdrawal:view"))
                .andExpect(jsonPath("$.permissions[2]").value("ops:withdrawal:action"))
                .andExpect(jsonPath("$.memberCount").value(0))
                .andExpect(jsonPath("$.builtIn").value(false));

        ArgumentCaptor<StaffRoleDefinition> created = ArgumentCaptor.forClass(StaffRoleDefinition.class);
        verify(keycloakAdminService).createStaffRole(created.capture());
        assertThat(created.getValue().permissions())
                .containsExactly("ops:dashboard:view", "ops:withdrawal:view", "ops:withdrawal:action");
    }

    @Test
    void unknownPermissionIsRejected() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Thử\",\"permissions\":[\"ops:staff-role:action\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Thử\",\"permissions\":[\"user:admin:read_all\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void blankNameIsValidationError() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"permissions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"!!!\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STAFF_ROLE_NAME"));
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void updateSyncsPermissionsAndLogsOutMembers() throws Exception {
        authenticateWith("ROLE_ADMIN");
        UUID member = UUID.fromString("99999999-8888-7777-6666-555555555555");
        when(keycloakAdminService.findStaffRole("STAFF_THU_HOI_NO")).thenReturn(Optional.of(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of("ops:collection:view"))));
        when(userProfileRepository.findKeycloakUserIdsByStaffRoleCode("STAFF_THU_HOI_NO")).thenReturn(List.of(member));

        mockMvc.perform(put(BASE + "/STAFF_THU_HOI_NO").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Thu hồi nợ cấp 2\",\"permissions\":[\"ops:reschedule:action\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("STAFF_THU_HOI_NO"))
                .andExpect(jsonPath("$.name").value("Thu hồi nợ cấp 2"))
                .andExpect(jsonPath("$.permissions[0]").value("ops:reschedule:view"))
                .andExpect(jsonPath("$.memberCount").value(1));

        ArgumentCaptor<StaffRoleDefinition> updated = ArgumentCaptor.forClass(StaffRoleDefinition.class);
        verify(keycloakAdminService).updateStaffRole(updated.capture());
        assertThat(updated.getValue().permissions()).containsExactly("ops:reschedule:view", "ops:reschedule:action");
        verify(keycloakAdminService).logoutUser(member.toString());
    }

    @Test
    void updateUnknownRoleIs404() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(keycloakAdminService.findStaffRole("STAFF_KHONG_CO")).thenReturn(Optional.empty());

        mockMvc.perform(put(BASE + "/STAFF_KHONG_CO").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"permissions\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_ROLE_NOT_FOUND"));
        mockMvc.perform(put(BASE + "/ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"permissions\":[]}"))
                .andExpect(status().isNotFound());
        verify(keycloakAdminService, never()).updateStaffRole(any());
    }

    @Test
    void deleteRoleInUseIsConflict() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(keycloakAdminService.findStaffRole("STAFF_THU_HOI_NO")).thenReturn(Optional.of(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of())));
        when(userProfileRepository.countByStaffRoleCode("STAFF_THU_HOI_NO")).thenReturn(2L);

        mockMvc.perform(delete(BASE + "/STAFF_THU_HOI_NO"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_ROLE_IN_USE"));
        verify(keycloakAdminService, never()).deleteStaffRole(anyString());
    }

    @Test
    void deleteBuiltInRoleIsConflict() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(delete(BASE + "/" + StaffRoleService.BUILT_IN_CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_ROLE_BUILT_IN"));
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void deleteUnusedRoleReturnsNoContent() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(keycloakAdminService.findStaffRole("STAFF_THU_HOI_NO")).thenReturn(Optional.of(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of())));
        when(userProfileRepository.countByStaffRoleCode("STAFF_THU_HOI_NO")).thenReturn(0L);

        mockMvc.perform(delete(BASE + "/STAFF_THU_HOI_NO")).andExpect(status().isNoContent());
        verify(keycloakAdminService).deleteStaffRole("STAFF_THU_HOI_NO");
    }

    @Test
    void staffWithEveryOpsPermissionIsForbiddenEverywhere() throws Exception {
        List<String> authorities = new ArrayList<>(OpsFunction.allPermissions());
        authorities.add("ROLE_STAFF");
        authorities.add("user:profile:read");
        authorities.add("user:admin:read_all");
        authorities.add("user:admin:assign_role");
        authenticateWith(authorities.toArray(String[]::new));

        String body = "{\"name\":\"Tự nâng quyền\",\"permissions\":[\"ops:customer:action\"]}";
        List<RequestBuilder> requests = List.of(
                get(BASE + "/permissions"),
                get(BASE),
                post(BASE).contentType(MediaType.APPLICATION_JSON).content(body),
                put(BASE + "/STAFF_VAN_HANH").contentType(MediaType.APPLICATION_JSON).content(body),
                delete(BASE + "/STAFF_THU_HOI_NO"));
        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(keycloakAdminService, userProfileRepository);
    }
}
