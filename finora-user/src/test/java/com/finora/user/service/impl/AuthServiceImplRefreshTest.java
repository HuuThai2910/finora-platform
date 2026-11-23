package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.PendingRegistrationStore;
import com.finora.user.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Làm mới phiên khi tài khoản đã bị quản trị viên khóa: báo đúng "tài khoản bị khóa" thay vì "phiên hết hạn". */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplRefreshTest {

    private static final UUID KC_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final BusinessException SESSION_EXPIRED =
            new BusinessException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");

    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private NotificationService notificationService;
    @Mock private PendingRegistrationStore pendingRegistrationStore;
    @Mock private CryptoProperties cryptoProperties;

    @InjectMocks private AuthServiceImpl authService;

    private static String refreshTokenOf(UUID subject) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"exp\":1795448562,\"sub\":\"" + subject + "\",\"typ\":\"Refresh\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".chu-ky";
    }

    private static UserProfile profile(boolean locked) {
        UserProfile profile = UserProfile.builder().id(27L).keycloakUserId(KC_ID).email("kh@finora.test")
                .role(UserRole.INVESTOR).build();
        if (locked) {
            profile.markLocked("Nghi gian lận", UUID.randomUUID(), Instant.parse("2026-11-23T15:00:00Z"));
        }
        return profile;
    }

    @Test
    void lockedOwnerGetsAccountLockedInsteadOfSessionExpired() {
        String token = refreshTokenOf(KC_ID);
        when(keycloakAdminService.refreshToken(token)).thenThrow(SESSION_EXPIRED);
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile(true)));

        assertThatThrownBy(() -> authService.refresh(token))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("ACCOUNT_LOCKED");
                    assertThat(e.getMessage()).isEqualTo("Tài khoản đã bị khóa. Vui lòng liên hệ bộ phận hỗ trợ FINORA.");
                });
    }

    @Test
    void activeOwnerKeepsSessionExpired() {
        String token = refreshTokenOf(KC_ID);
        when(keycloakAdminService.refreshToken(token)).thenThrow(SESSION_EXPIRED);
        when(userProfileRepository.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(profile(false)));

        assertThatThrownBy(() -> authService.refresh(token)).isSameAs(SESSION_EXPIRED);
    }

    @Test
    void malformedTokenKeepsSessionExpiredWithoutLookup() {
        when(keycloakAdminService.refreshToken("khong-phai-jwt")).thenThrow(SESSION_EXPIRED);

        assertThatThrownBy(() -> authService.refresh("khong-phai-jwt")).isSameAs(SESSION_EXPIRED);
        verify(userProfileRepository, never()).findByKeycloakUserId(any());
    }

    @Test
    void subjectIsReadFromPayload() {
        assertThat(AuthServiceImpl.tokenSubject(refreshTokenOf(KC_ID))).contains(KC_ID);
        assertThat(AuthServiceImpl.tokenSubject(null)).isEmpty();
        assertThat(AuthServiceImpl.tokenSubject("a.%%%.c")).isEmpty();
    }
}
