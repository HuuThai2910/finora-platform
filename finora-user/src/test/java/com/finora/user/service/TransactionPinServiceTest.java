package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.PinTokenService;
import com.finora.user.domain.UserPin;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.PinTokenResponse;
import com.finora.user.repository.UserPinRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.TransactionPinService.ClientInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.AccessTokenResponse;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra vòng đời mã PIN giao dịch: tạo, kiểm, khoá, đặt lại. */
@ExtendWith(MockitoExtension.class)
class TransactionPinServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
    private static final ClientInfo CLIENT = new ClientInfo("10.0.0.1", "jest");
    private static final String PIN = "394817";

    @Mock
    private UserPinRepository pinRepository;
    @Mock
    private UserProfileRepository profileRepository;
    @Mock
    private KeycloakAdminService keycloakAdminService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private NotificationService notificationService;

    private final PinTokenService pinTokenService = new PinTokenService(
            "test-pin-token-secret-at-least-32-bytes!", Duration.ofMinutes(3), Clock.fixed(NOW, ZoneOffset.UTC));

    private TransactionPinService service;

    @BeforeEach
    void setUp() {
        service = new TransactionPinService(pinRepository, profileRepository, pinTokenService,
                keycloakAdminService, rateLimitService, notificationService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void chuaCoPinThiTrangThaiBaoChuaCo() {
        when(pinRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.status(USER_ID).hasPin()).isFalse();
    }

    @Test
    void taoPinDeDoanBiTuChoi() {
        when(profileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(pinRepository.existsById(USER_ID)).thenReturn(false);

        assertCode(() -> service.create(USER_ID, "123456"), "PIN_TOO_WEAK");
        verify(pinRepository, never()).saveAndFlush(any());
    }

    @Test
    void taoPinLanHaiBiTuChoi() {
        when(profileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(pinRepository.existsById(USER_ID)).thenReturn(true);

        assertCode(() -> service.create(USER_ID, PIN), "PIN_ALREADY_SET");
    }

    @Test
    void nhapDungPinNhanDuocTokenDungLoaiThaoTac() {
        when(pinRepository.findForUpdate(USER_ID)).thenReturn(Optional.of(storedPin()));

        PinTokenResponse response = service.verify(USER_ID, PIN, PinScope.ORDER, CLIENT);

        pinTokenService.verify(response.pinToken(), USER_ID.toString(), PinScope.ORDER);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
    }

    @Test
    void nhapSaiPinBaoSoLanConLai() {
        UserPin pin = storedPin();
        when(pinRepository.findForUpdate(USER_ID)).thenReturn(Optional.of(pin));

        assertThatThrownBy(() -> service.verify(USER_ID, "000001", PinScope.ORDER, CLIENT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("còn 4 lần")
                .extracting("code").isEqualTo("PIN_INCORRECT");
        assertThat(pin.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void saiNamLanThiKhoaVaGuiCanhBao() {
        UserPin pin = storedPin();
        when(pinRepository.findForUpdate(USER_ID)).thenReturn(Optional.of(pin));
        when(profileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile()));

        for (int i = 0; i < UserPin.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertCode(() -> service.verify(USER_ID, "000001", PinScope.ORDER, CLIENT), "PIN_INCORRECT");
        }
        assertCode(() -> service.verify(USER_ID, "000001", PinScope.ORDER, CLIENT), "PIN_LOCKED");

        assertThat(pin.isLocked(NOW)).isTrue();
        verify(notificationService).sendSuspiciousActivityAlert(eq(7L), eq("hai@finora.vn"),
                eq("10.0.0.1"), eq("jest"), anyString());
        // Đang khoá thì nhập đúng cũng bị từ chối.
        assertCode(() -> service.verify(USER_ID, PIN, PinScope.ORDER, CLIENT), "PIN_LOCKED");
    }

    @Test
    void doiPinCanPinCuDung() {
        when(pinRepository.findForUpdate(USER_ID)).thenReturn(Optional.of(storedPin()));

        assertCode(() -> service.change(USER_ID, "000001", "582916", CLIENT), "PIN_INCORRECT");
    }

    @Test
    void quenPinSaiMatKhauBiTuChoi() {
        when(profileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(keycloakAdminService.getUserToken("hai@finora.vn", "sai"))
                .thenThrow(new BusinessException(HttpStatus.UNAUTHORIZED, "sai"));

        assertCode(() -> service.reset(USER_ID, "sai", "582916", CLIENT), "PASSWORD_INCORRECT");
        verify(rateLimitService).recordFailedLogin("hai@finora.vn", "10.0.0.1");
    }

    @Test
    void quenPinDungMatKhauThiDatPinMoiVaGoKhoa() {
        UserPin pin = storedPin();
        for (int i = 0; i < UserPin.MAX_FAILED_ATTEMPTS; i++) {
            pin.recordFailure(NOW);
        }
        AccessTokenResponse session = new AccessTokenResponse();
        session.setRefreshToken("refresh");
        when(profileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(keycloakAdminService.getUserToken("hai@finora.vn", "dung-mat-khau")).thenReturn(session);
        when(pinRepository.findForUpdate(USER_ID)).thenReturn(Optional.of(pin));

        assertThat(service.reset(USER_ID, "dung-mat-khau", "582916", CLIENT).locked()).isFalse();
        assertThat(new BCryptPasswordEncoder().matches("582916", pin.getPinHash())).isTrue();
        verify(keycloakAdminService).revokeRefreshToken("refresh");
    }

    private static UserPin storedPin() {
        return UserPin.create(USER_ID, new BCryptPasswordEncoder().encode(PIN), NOW);
    }

    private static UserProfile profile() {
        return UserProfile.builder().id(7L).keycloakUserId(USER_ID).email("hai@finora.vn").build();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }
}
