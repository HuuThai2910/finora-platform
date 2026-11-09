package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.UserLoginDevice;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.LoginClientType;
import com.finora.user.domain.model.LoginDeviceContext;
import com.finora.user.dto.response.LoginDeviceResponse;
import com.finora.user.repository.UserLoginDeviceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginDeviceServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID KEYCLOAK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final UserLoginDeviceRepository devices = mock(UserLoginDeviceRepository.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final KeycloakAdminService keycloak = mock(KeycloakAdminService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private LoginDeviceServiceImpl service;

    @BeforeEach
    void setUp() {
        // Chạy callback ngay như một transaction thật.
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        service = new LoginDeviceServiceImpl(devices, profiles, keycloak, transactionTemplate, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(42L);
        when(profiles.findByKeycloakUserId(KEYCLOAK_ID)).thenReturn(Optional.of(profile));
    }

    @Test
    void newDeviceIsInsertedWithSessionIdFromToken() {
        when(devices.findByUserProfileIdAndDeviceId(42L, "device-0001")).thenReturn(Optional.empty());

        service.recordLogin(42L, context("device-0001"), token("{\"sid\":\"session-abc\",\"sub\":\"x\"}"));

        ArgumentCaptor<UserLoginDevice> saved = ArgumentCaptor.forClass(UserLoginDevice.class);
        verify(devices).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserProfileId()).isEqualTo(42L);
        assertThat(saved.getValue().getKeycloakSessionId()).isEqualTo("session-abc");
        assertThat(saved.getValue().getLoginCount()).isEqualTo(1);
        assertThat(saved.getValue().getFirstLoginAt()).isEqualTo(NOW);
    }

    @Test
    void knownDeviceIsUpdatedNotDuplicated() {
        UserLoginDevice existing = UserLoginDevice.firstLogin(42L, context("device-0001"), "old-session",
                NOW.minusSeconds(86_400));
        when(devices.findByUserProfileIdAndDeviceId(42L, "device-0001")).thenReturn(Optional.of(existing));

        service.recordLogin(42L, context("device-0001"), token("{\"sid\":\"new-session\"}"));

        verify(devices, never()).saveAndFlush(any());
        assertThat(existing.getLoginCount()).isEqualTo(2);
        assertThat(existing.getLastLoginAt()).isEqualTo(NOW);
        assertThat(existing.getFirstLoginAt()).isEqualTo(NOW.minusSeconds(86_400));
        assertThat(existing.getKeycloakSessionId()).isEqualTo("new-session");
    }

    @Test
    void recordingFailureNeverBreaksLogin() {
        when(devices.findByUserProfileIdAndDeviceId(any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatCode(() -> service.recordLogin(42L, context("device-0001"), "not-a-jwt")).doesNotThrowAnyException();
    }

    @Test
    void malformedTokenStillRecordsDeviceWithoutSession() {
        assertThat(service.sessionId("not-a-jwt")).isNull();
        assertThat(service.sessionId(null)).isNull();
        assertThat(service.sessionId(token("{\"sub\":\"x\"}"))).isNull();
    }

    @Test
    void ownerSeesOnlyOwnDevicesWithMaskedIpAndCurrentFlag() {
        UserLoginDevice phone = withId(UserLoginDevice.firstLogin(42L, context("device-0001"), "s1", NOW), 1L);
        UserLoginDevice laptop = withId(UserLoginDevice.firstLogin(42L, context("device-0002"), "s2", NOW), 2L);
        when(devices.findByUserProfileIdOrderByLastLoginAtDescIdDesc(42L)).thenReturn(List.of(phone, laptop));

        List<LoginDeviceResponse> result = service.myDevices(KEYCLOAK_ID, "device-0001", null);

        verify(devices).findByUserProfileIdOrderByLastLoginAtDescIdDesc(42L);
        assertThat(result).extracting(LoginDeviceResponse::current).containsExactly(true, false);
        assertThat(result).extracting(LoginDeviceResponse::canSignOut).containsExactly(false, true);
        assertThat(result.getFirst().ipAddress()).isEqualTo("113.161.*.*");
    }

    @Test
    void supportStaffSeesFullIp() {
        when(profiles.existsById(42L)).thenReturn(true);
        when(devices.findByUserProfileIdOrderByLastLoginAtDescIdDesc(42L)).thenReturn(
                List.of(withId(UserLoginDevice.firstLogin(42L, context("device-0001"), "s1", NOW), 1L)));

        assertThat(service.devicesOf(42L).getFirst().ipAddress()).isEqualTo("113.161.10.20");
    }

    @Test
    void signingOutAnotherDeviceDeletesItsKeycloakSession() {
        UserLoginDevice laptop = withId(UserLoginDevice.firstLogin(42L, context("device-0002"), "s2", NOW), 2L);
        when(devices.findByIdAndUserProfileId(2L, 42L)).thenReturn(Optional.of(laptop));

        service.signOut(KEYCLOAK_ID, 2L, "device-0001", "s1");

        verify(keycloak).deleteUserSession(KEYCLOAK_ID.toString(), "s2");
        verify(devices).deleteById(2L);
    }

    @Test
    void currentDeviceCannotBeSignedOutHere() {
        UserLoginDevice phone = withId(UserLoginDevice.firstLogin(42L, context("device-0001"), "s1", NOW), 1L);
        when(devices.findByIdAndUserProfileId(1L, 42L)).thenReturn(Optional.of(phone));

        assertThatThrownBy(() -> service.signOut(KEYCLOAK_ID, 1L, "device-0001", "s1"))
                .isInstanceOf(BusinessException.class);
        verify(keycloak, never()).deleteUserSession(anyString(), anyString());
    }

    @Test
    void deviceOfAnotherUserIsNotFound() {
        when(devices.findByIdAndUserProfileId(9L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signOut(KEYCLOAK_ID, 9L, "device-0001", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static LoginDeviceContext context(String deviceId) {
        return new LoginDeviceContext(deviceId, "iPhone 15 Pro", "iOS 18.1", LoginClientType.MOBILE, "1.4.0",
                "113.161.10.20");
    }

    private static UserLoginDevice withId(UserLoginDevice device, Long id) {
        ReflectionTestUtils.setField(device, "id", id);
        return device;
    }

    private static String token(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
