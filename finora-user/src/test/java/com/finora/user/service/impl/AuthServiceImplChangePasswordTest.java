package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.dto.request.ChangePasswordRequest;
import com.finora.user.dto.request.LoginRequest;
import com.finora.user.dto.response.AuthResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.PendingRegistrationStore;
import com.finora.user.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.AccessTokenResponse;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
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
 * Tự đổi mật khẩu (màn đổi mật khẩu bắt buộc của nhân viên mới) và cờ {@code mustChangePassword} trong phản hồi đăng
 * nhập.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplChangePasswordTest {

    private static final UUID KC_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String EMAIL = "nv.nam@finora.vn";

    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private NotificationService notificationService;
    @Mock private PendingRegistrationStore pendingRegistrationStore;
    @Mock private CryptoProperties cryptoProperties;

    @InjectMocks private AuthServiceImpl authService;

    private UserProfile profile;

    @BeforeEach
    void setUp() {
        profile = UserProfile.builder().id(77L).keycloakUserId(KC_ID).email(EMAIL).role(UserRole.STAFF)
                .staffRoleCode("STAFF_VAN_HANH").mustChangePassword(true).build();
    }

    private static AccessTokenResponse token(String refresh) {
        AccessTokenResponse response = new AccessTokenResponse();
        response.setToken("access");
        response.setRefreshToken(refresh);
        return response;
    }

    @Test
    void changeVerifiesCurrentRevokesProbeSessionSetsPasswordAndClearsFlag() {
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile));
        when(keycloakAdminService.getUserToken(EMAIL, "TamThoi#2345")).thenReturn(token("probe-refresh"));

        authService.changePassword(KC_ID, new ChangePasswordRequest("TamThoi#2345", "MatKhauMoi!9"), "10.0.0.1");

        InOrder order = inOrder(keycloakAdminService, userProfileRepository);
        order.verify(keycloakAdminService).getUserToken(EMAIL, "TamThoi#2345");
        order.verify(keycloakAdminService).revokeRefreshToken("probe-refresh");
        order.verify(keycloakAdminService).resetPassword(KC_ID.toString(), "MatKhauMoi!9");
        order.verify(userProfileRepository).save(profile);
        assertThat(profile.isMustChangePassword()).isFalse();
        verify(rateLimitService).resetFailedLogin(EMAIL);
    }

    @Test
    void wrongCurrentPasswordCountsAsFailedLoginAndChangesNothing() {
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile));
        when(keycloakAdminService.getUserToken(EMAIL, "SaiMatKhau1"))
                .thenThrow(new BusinessException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"));

        assertThatThrownBy(() -> authService.changePassword(KC_ID,
                new ChangePasswordRequest("SaiMatKhau1", "MatKhauMoi!9"), "10.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("CURRENT_PASSWORD_INVALID");
                });
        verify(rateLimitService).recordFailedLogin(EMAIL, "10.0.0.1");
        verify(keycloakAdminService, never()).resetPassword(anyString(), anyString());
        verify(userProfileRepository, never()).save(any());
        assertThat(profile.isMustChangePassword()).isTrue();
    }

    @Test
    void samePasswordIsRejectedBeforeKeycloak() {
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> authService.changePassword(KC_ID,
                new ChangePasswordRequest("GiongNhau#1", "GiongNhau#1"), "10.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PASSWORD_UNCHANGED"));
        verify(keycloakAdminService, never()).getUserToken(anyString(), anyString());
    }

    @Test
    void blockedAccountCannotProbePasswords() {
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile));
        when(rateLimitService.isLoginBlocked(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(KC_ID,
                new ChangePasswordRequest("TamThoi#2345", "MatKhauMoi!9"), "10.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(keycloakAdminService, never()).getUserToken(anyString(), anyString());
    }

    @Test
    void loginResponseCarriesMustChangePasswordFlag() {
        when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(profile));
        when(keycloakAdminService.getUserToken(EMAIL, "TamThoi#2345")).thenReturn(token("r"));

        AuthResponse response = authService.login(new LoginRequest(EMAIL, "TamThoi#2345"), "10.0.0.1", "test");

        assertThat(response.getMustChangePassword()).isTrue();
    }
}
