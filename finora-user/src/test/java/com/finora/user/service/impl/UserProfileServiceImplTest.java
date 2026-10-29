package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.mapper.UserProfileMapperImpl;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.StaffRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gán vai trò (thay cả bộ realm role, đăng xuất người dùng, ghi mã vai trò nhân viên) và {@code /users/me}
 * (quyền ops:* từ JWT, vai trò nhân viên).
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    private static final UUID ADMIN_KC_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_KC_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC);

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private KeycloakAdminService keycloakAdminService;

    private UserProfileServiceImpl service;
    private UserProfile target;

    @BeforeEach
    void setUp() {
        StaffRoleService staffRoleService = new StaffRoleService(keycloakAdminService, userProfileRepository, CLOCK);
        service = new UserProfileServiceImpl(userProfileRepository, new UserProfileMapperImpl(),
                keycloakAdminService, staffRoleService);
        target = UserProfile.builder().id(42L).keycloakUserId(TARGET_KC_ID).email("nhanvien@example.test")
                .role(UserRole.BORROWER).build();
    }

    @Test
    void assignStaffReplacesOldRealmRolesThenLogsOut() {
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));
        when(keycloakAdminService.findStaffRole("STAFF_VAN_HANH")).thenReturn(Optional.of(new StaffRoleDefinition(
                "STAFF_VAN_HANH", "Nhân viên vận hành", null, OpsFunction.defaultStaffPermissions())));

        service.assignRole(42L, "staff", "STAFF_VAN_HANH", ADMIN_KC_ID);

        InOrder order = inOrder(keycloakAdminService, userProfileRepository);
        order.verify(keycloakAdminService).replaceRealmRoles(TARGET_KC_ID.toString(),
                List.of("ROLE_STAFF", "STAFF_VAN_HANH"));
        order.verify(userProfileRepository).save(target);
        order.verify(keycloakAdminService).logoutUser(TARGET_KC_ID.toString());
        verify(keycloakAdminService, never()).assignRole(anyString(), anyString());
        assertThat(target.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(target.getStaffRoleCode()).isEqualTo("STAFF_VAN_HANH");
    }

    @Test
    void assignNonStaffClearsStaffRoleCode() {
        target.setRole(UserRole.STAFF);
        target.setStaffRoleCode("STAFF_VAN_HANH");
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        service.assignRole(42L, "INVESTOR", "  ", ADMIN_KC_ID);

        verify(keycloakAdminService).replaceRealmRoles(TARGET_KC_ID.toString(), List.of("ROLE_INVESTOR"));
        verify(keycloakAdminService).logoutUser(TARGET_KC_ID.toString());
        assertThat(target.getRole()).isEqualTo(UserRole.INVESTOR);
        assertThat(target.getStaffRoleCode()).isNull();
    }

    @Test
    void staffWithoutStaffRoleCodeIsRejected() {
        assertThatThrownBy(() -> service.assignRole(42L, "STAFF", null, ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_REQUIRED"));
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
    }

    @Test
    void staffRoleCodeOnNonStaffIsRejected() {
        assertThatThrownBy(() -> service.assignRole(42L, "ADMIN", "STAFF_VAN_HANH", ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_NOT_ALLOWED"));
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
    }

    @Test
    void unknownStaffRoleIsNotFoundAndNothingChanges() {
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));
        when(keycloakAdminService.findStaffRole("STAFF_KHONG_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignRole(42L, "STAFF", "STAFF_KHONG_CO", ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_NOT_FOUND"));
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void selfAssignmentIsStillBlocked() {
        target.setKeycloakUserId(ADMIN_KC_ID);
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.assignRole(42L, "BORROWER", null, ADMIN_KC_ID))
                .isInstanceOf(BusinessException.class);
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
    }

    @Test
    void meForAdminListsWholeCatalog() {
        target.setRole(UserRole.ADMIN);
        when(userProfileRepository.findByKeycloakUserId(TARGET_KC_ID)).thenReturn(Optional.of(target));

        UserProfileResponse me = service.getMyProfile(TARGET_KC_ID,
                Set.of("ROLE_ADMIN", "user:profile:read", "user:admin:read_all"));

        assertThat(me.getPermissions()).isEqualTo(OpsFunction.allPermissions());
        assertThat(me.getStaffRole()).isNull();
        assertThat(me.getStaffRoleCode()).isNull();
    }

    @Test
    void meForStaffListsOnlyOpsPermissionsInCatalogOrderWithRoleName() {
        target.setRole(UserRole.STAFF);
        target.setStaffRoleCode("STAFF_THU_HOI_NO");
        when(userProfileRepository.findByKeycloakUserId(TARGET_KC_ID)).thenReturn(Optional.of(target));
        when(keycloakAdminService.listStaffRoles()).thenReturn(List.of(new StaffRoleDefinition(
                "STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of())));

        UserProfileResponse me = service.getMyProfile(TARGET_KC_ID, Set.of("ROLE_STAFF", "STAFF_THU_HOI_NO",
                "user:profile:read", "ops:collection:action", "ops:dashboard:view", "ops:collection:view"));

        assertThat(me.getPermissions())
                .containsExactly("ops:dashboard:view", "ops:collection:view", "ops:collection:action");
        assertThat(me.getStaffRole()).isEqualTo(new UserProfileResponse.StaffRoleRef("STAFF_THU_HOI_NO", "Thu hồi nợ"));
        assertThat(me.getStaffRoleName()).isEqualTo("Thu hồi nợ");
        assertThat(me.getRole()).isEqualTo(UserRole.STAFF);
    }

    @Test
    void meForBorrowerHasNoPermissions() {
        when(userProfileRepository.findByKeycloakUserId(TARGET_KC_ID)).thenReturn(Optional.of(target));

        UserProfileResponse me = service.getMyProfile(TARGET_KC_ID, Set.of("ROLE_BORROWER", "user:profile:read"));

        assertThat(me.getPermissions()).isEmpty();
        assertThat(me.getStaffRole()).isNull();
    }

    @Test
    void staffRoleNameFallsBackToCodeWhenKeycloakFails() {
        target.setRole(UserRole.STAFF);
        target.setStaffRoleCode("STAFF_THU_HOI_NO");
        when(userProfileRepository.findByKeycloakUserId(TARGET_KC_ID)).thenReturn(Optional.of(target));
        when(keycloakAdminService.listStaffRoles()).thenThrow(new BusinessException("Keycloak lỗi"));

        UserProfileResponse me = service.getMyProfile(TARGET_KC_ID, Set.of("ROLE_STAFF"));
        service.getMyProfile(TARGET_KC_ID, Set.of("ROLE_STAFF"));

        assertThat(me.getStaffRoleName()).isEqualTo("STAFF_THU_HOI_NO");
        // Lỗi cũng được nhớ 60 giây: lần gọi thứ hai không gọi lại Keycloak.
        verify(keycloakAdminService, org.mockito.Mockito.times(1)).listStaffRoles();
    }
}
