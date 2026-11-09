package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.model.StaffRoleDefinition;
import com.finora.user.dto.request.CreateStaffAccountRequest;
import com.finora.user.dto.response.StaffAccountCreatedResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.StaffRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tạo tài khoản nội bộ: chỉ STAFF/ADMIN, kiểm trùng trước khi tạo Keycloak user, gán đủ realm role, hồ sơ bật cờ đổi
 * mật khẩu, lỗi giữa chừng dọn user Keycloak, mật khẩu tạm trả một lần và gửi email best-effort.
 */
@ExtendWith(MockitoExtension.class)
class StaffAccountServiceImplTest {

    private static final String KC_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private StaffRoleService staffRoleService;
    @Mock private NotificationService notificationService;

    private StaffAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        CryptoProperties crypto = new CryptoProperties();
        crypto.setHmacSecret("test-hmac-secret");
        service = new StaffAccountServiceImpl(keycloakAdminService, userProfileRepository, staffRoleService,
                notificationService, crypto);
    }

    private static CreateStaffAccountRequest staffRequest() {
        return new CreateStaffAccountRequest("  Lê Văn Nam ", " NV.Nam@Finora.VN ", "0912345678", "staff",
                "STAFF_THU_HOI_NO");
    }

    private void savesWithId(long id) {
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> {
            UserProfile profile = invocation.getArgument(0);
            profile.setId(id);
            return profile;
        });
    }

    @Test
    void createsStaffWithRolesProfileFlagAndReturnsPasswordOnce() {
        when(staffRoleService.requireStaffRole("STAFF_THU_HOI_NO")).thenReturn(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of()));
        when(keycloakAdminService.createUser(eq("nv.nam@finora.vn"), anyString(), eq("Lê Văn Nam"), eq(UserRole.STAFF)))
                .thenReturn(KC_ID);
        savesWithId(77L);
        when(notificationService.sendStaffAccountEmail(eq(77L), eq("nv.nam@finora.vn"), eq("Lê Văn Nam"),
                eq("Nhân viên (Thu hồi nợ)"), anyString())).thenReturn(true);

        StaffAccountCreatedResponse response = service.createStaffAccount(staffRequest());

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(keycloakAdminService, userProfileRepository, notificationService);
        order.verify(keycloakAdminService).createUser(eq("nv.nam@finora.vn"), password.capture(), eq("Lê Văn Nam"),
                eq(UserRole.STAFF));
        order.verify(keycloakAdminService).assignRole(KC_ID, "STAFF_THU_HOI_NO");
        order.verify(userProfileRepository).save(any(UserProfile.class));
        order.verify(keycloakAdminService).setUserIdAttribute(KC_ID, 77L);
        order.verify(notificationService).sendStaffAccountEmail(77L, "nv.nam@finora.vn", "Lê Văn Nam",
                "Nhân viên (Thu hồi nợ)", password.getValue());

        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(saved.capture());
        UserProfile profile = saved.getValue();
        assertThat(profile.getKeycloakUserId()).isEqualTo(UUID.fromString(KC_ID));
        assertThat(profile.getEmail()).isEqualTo("nv.nam@finora.vn");
        assertThat(profile.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(profile.getStaffRoleCode()).isEqualTo("STAFF_THU_HOI_NO");
        assertThat(profile.isMustChangePassword()).isTrue();
        assertThat(profile.getPhoneEncrypted()).isEqualTo("0912345678");
        assertThat(profile.getPhoneHash()).isNotBlank();

        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.role()).isEqualTo(UserRole.STAFF);
        assertThat(response.staffRoleName()).isEqualTo("Thu hồi nợ");
        assertThat(response.temporaryPassword()).isEqualTo(password.getValue()).hasSize(14);
        assertThat(response.credentialsEmailSent()).isTrue();
        verify(keycloakAdminService, never()).deleteUser(anyString());
    }

    @Test
    void adminNeedsNoStaffRoleAndEmailFailureDoesNotFailCreation() {
        when(keycloakAdminService.createUser(eq("quantri@finora.vn"), anyString(), eq("Trần Thu"), eq(UserRole.ADMIN)))
                .thenReturn(KC_ID);
        savesWithId(5L);
        when(notificationService.sendStaffAccountEmail(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        StaffAccountCreatedResponse response = service.createStaffAccount(
                new CreateStaffAccountRequest("Trần Thu", "quantri@finora.vn", "", "ADMIN", " "));

        verify(keycloakAdminService, never()).assignRole(anyString(), anyString());
        verifyNoInteractions(staffRoleService);
        assertThat(response.staffRoleCode()).isNull();
        assertThat(response.credentialsEmailSent()).isFalse();
        verify(notificationService).sendStaffAccountEmail(eq(5L), eq("quantri@finora.vn"), eq("Trần Thu"),
                eq("Quản trị viên"), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BORROWER", "INVESTOR"})
    void customerRolesCannotBeCreatedHere(String role) {
        assertThatThrownBy(() -> service.createStaffAccount(
                new CreateStaffAccountRequest("A", "a@finora.vn", null, role, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("ROLE_NOT_ASSIGNABLE");
                });
        verifyNoInteractions(keycloakAdminService, userProfileRepository);
    }

    @Test
    void staffWithoutStaffRoleIsRejected() {
        assertThatThrownBy(() -> service.createStaffAccount(
                new CreateStaffAccountRequest("A", "a@finora.vn", null, "STAFF", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STAFF_ROLE_REQUIRED"));
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void duplicateEmailIsConflictBeforeKeycloak() {
        when(staffRoleService.requireStaffRole("STAFF_THU_HOI_NO")).thenReturn(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of()));
        when(userProfileRepository.existsByEmail("nv.nam@finora.vn")).thenReturn(true);

        assertThatThrownBy(() -> service.createStaffAccount(staffRequest()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
                });
        verifyNoInteractions(keycloakAdminService, notificationService);
    }

    @Test
    void duplicatePhoneIsConflictBeforeKeycloak() {
        when(staffRoleService.requireStaffRole("STAFF_THU_HOI_NO")).thenReturn(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of()));
        when(userProfileRepository.existsByPhoneHash(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.createStaffAccount(staffRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PHONE_TAKEN"));
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void failureAfterKeycloakUserIsCreatedDeletesThatUser() {
        when(staffRoleService.requireStaffRole("STAFF_THU_HOI_NO")).thenReturn(
                new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", null, List.of()));
        when(keycloakAdminService.createUser(anyString(), anyString(), anyString(), eq(UserRole.STAFF)))
                .thenReturn(KC_ID);
        savesWithId(9L);
        doThrow(new BusinessException("Keycloak lỗi")).when(keycloakAdminService).setUserIdAttribute(KC_ID, 9L);

        assertThatThrownBy(() -> service.createStaffAccount(staffRequest()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.getCode()).isEqualTo("STAFF_ACCOUNT_CREATE_FAILED");
                });
        verify(keycloakAdminService).deleteUser(KC_ID);
        verifyNoInteractions(notificationService);
    }
}
