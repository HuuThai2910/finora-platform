package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.model.StaffRoleDefinition;
import com.finora.user.dto.request.UserSearchCriteria;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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
 * Gán vai trò (thay cả bộ realm role, đăng xuất người dùng, ghi mã vai trò nhân viên; chỉ giữa tài khoản nội bộ),
 * lọc theo nhóm tài khoản và {@code /users/me}
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
        StaffRoleService staffRoleService = new StaffRoleServiceImpl(keycloakAdminService, userProfileRepository, CLOCK);
        CryptoProperties crypto = new CryptoProperties();
        crypto.setHmacSecret("test-hmac-secret");
        service = new UserProfileServiceImpl(userProfileRepository, new UserProfileMapperImpl(),
                keycloakAdminService, staffRoleService, crypto, CLOCK);
        target = UserProfile.builder().id(42L).keycloakUserId(TARGET_KC_ID).email("nhanvien@example.test")
                .role(UserRole.BORROWER).build();
    }

    @Test
    void assignStaffReplacesOldRealmRolesThenLogsOut() {
        target.setRole(UserRole.ADMIN);
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
    void promotingStaffToAdminClearsStaffRoleCode() {
        target.setRole(UserRole.STAFF);
        target.setStaffRoleCode("STAFF_VAN_HANH");
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        service.assignRole(42L, "ADMIN", "  ", ADMIN_KC_ID);

        verify(keycloakAdminService).replaceRealmRoles(TARGET_KC_ID.toString(), List.of("ROLE_ADMIN"));
        verify(keycloakAdminService).logoutUser(TARGET_KC_ID.toString());
        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(target.getStaffRoleCode()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"BORROWER", "INVESTOR"})
    void staffCannotBeTurnedIntoCustomer(String customerRole) {
        assertThatThrownBy(() -> service.assignRole(42L, customerRole, null, ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("ROLE_NOT_ASSIGNABLE");
                });
        verify(userProfileRepository, never()).findById(any());
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = UserRole.class, names = {"BORROWER", "INVESTOR"})
    void customerAccountCannotBecomeStaff(UserRole customerRole) {
        target.setRole(customerRole);
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.assignRole(42L, "ADMIN", null, ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("CUSTOMER_ROLE_LOCKED");
                });
        verify(keycloakAdminService, never()).replaceRealmRoles(anyString(), any());
        verify(keycloakAdminService, never()).findStaffRole(anyString());
        verify(userProfileRepository, never()).save(any());
        assertThat(target.getRole()).isEqualTo(customerRole);
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
        target.setRole(UserRole.ADMIN);
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
        target.setRole(UserRole.ADMIN);
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.assignRole(42L, "STAFF", "STAFF_VAN_HANH", ADMIN_KC_ID))
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

    @Test
    void lockStoresReasonDisablesThenLogsOut() {
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        service.lockUser(42L, "  Nghi giả mạo giấy tờ  ", ADMIN_KC_ID);

        InOrder order = inOrder(keycloakAdminService, userProfileRepository);
        order.verify(keycloakAdminService).disableUser(TARGET_KC_ID.toString());
        order.verify(userProfileRepository).save(target);
        order.verify(keycloakAdminService).logoutUser(TARGET_KC_ID.toString());
        assertThat(target.getLockReason()).isEqualTo("Nghi giả mạo giấy tờ");
        assertThat(target.getLockedAt()).isEqualTo(CLOCK.instant());
        assertThat(target.getLockedByKeycloakId()).isEqualTo(ADMIN_KC_ID);
    }

    @Test
    void lockWithoutReasonIsRejected() {
        assertThatThrownBy(() -> service.lockUser(42L, "  ", ADMIN_KC_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LOCK_REASON_REQUIRED"));
        verify(keycloakAdminService, never()).disableUser(anyString());
    }

    @Test
    void unlockClearsReason() {
        target.markLocked("Lý do cũ", ADMIN_KC_ID, CLOCK.instant());
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(target));

        service.unlockUser(42L);

        verify(keycloakAdminService).enableUser(TARGET_KC_ID.toString());
        assertThat(target.getLockReason()).isNull();
        assertThat(target.getLockedAt()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchSortsNewestFirstAndCapsPageSize() {
        when(userProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target)));

        var result = service.searchUsers(new UserSearchCriteria(null, null, "STAFF_VAN_HANH", "Nguyễn"),
                PageRequest.of(0, 500));

        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(userProfileRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void staffRoleFilterOnNonStaffRoleIsRejected() {
        assertThatThrownBy(() -> service.searchUsers(new UserSearchCriteria("BORROWER", null, "STAFF_VAN_HANH", null),
                PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_NOT_ALLOWED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staffScopeIsAcceptedAndCaseInsensitive() {
        when(userProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = service.searchUsers(new UserSearchCriteria("ADMIN", null, null, null, "staff"),
                PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isZero();
        verify(userProfileRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void invalidAdvancedFilterIsRejectedBeforeQuery() {
        var filter = new com.finora.user.dto.request.UserListFilter(null, null, null, 40, 20, null, null, null);
        assertThatThrownBy(() -> service.searchUsers(new UserSearchCriteria(null, null, null, null, null, filter),
                PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
        verify(userProfileRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancedFilterStillUsesOnePagedQuery() {
        when(userProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        var filter = new com.finora.user.dto.request.UserListFilter(null, null, true, null, null, null, null, null);

        service.searchUsers(new UserSearchCriteria(null, null, null, null, "CUSTOMER", filter), PageRequest.of(0, 20));

        verify(userProfileRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void roleOutsideScopeIsRejected() {
        assertThatThrownBy(() -> service.searchUsers(new UserSearchCriteria("BORROWER", null, null, null, "STAFF"),
                PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ROLE_OUT_OF_SCOPE"));
        verify(userProfileRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void staffRoleFilterInCustomerScopeIsRejected() {
        assertThatThrownBy(() -> service.searchUsers(
                new UserSearchCriteria(null, null, "STAFF_VAN_HANH", null, "CUSTOMER"), PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_NOT_ALLOWED"));
    }

    @Test
    void unknownScopeIsRejected() {
        assertThatThrownBy(() -> service.searchUsers(new UserSearchCriteria(null, null, null, null, "VIP"),
                PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SCOPE_INVALID"));
    }

    @Test
    void identifierCandidatesCoverVietnamesePhonePrefixes() {
        assertThat(UserProfileServiceImpl.identifierCandidates("0912 345 678"))
                .contains("0912 345 678", "0912345678", "+84912345678", "84912345678");
        assertThat(UserProfileServiceImpl.identifierCandidates("+84912345678"))
                .contains("+84912345678", "84912345678", "0912345678");
        assertThat(UserProfileServiceImpl.identifierCandidates("079201001234")).contains("079201001234");
        assertThat(UserProfileServiceImpl.identifierCandidates("nguyen")).isEmpty();
    }
}
