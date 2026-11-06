package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Khoá đăng nhập sai, hạn mức gửi OTP và số lần thử OTP trên kho tạm — giữ nguyên key và TTL thời Redis. */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceImplTest {

    private static final String EMAIL = "nguoi.vay@example.com";
    private static final Long USER_ID = 42L;

    @Mock private TemporaryStore temporaryStore;

    private RateLimitServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RateLimitServiceImpl(temporaryStore);
    }

    @Test
    void loginBlockIsReadFromBlockKey() {
        when(temporaryStore.exists("login_block:" + EMAIL)).thenReturn(true);

        assertThat(service.isLoginBlocked(EMAIL)).isTrue();
    }

    @Test
    void failedLoginsCountInFiveMinuteWindowAndBlockFifteenMinutesAtFifth() {
        when(temporaryStore.incrementWithinWindow("login_fail:" + EMAIL, Duration.ofMinutes(5)))
                .thenReturn(4L, 5L);

        assertThat(service.recordFailedLogin(EMAIL, "10.0.0.1")).isEqualTo(4);
        verify(temporaryStore, never()).put(anyString(), anyString(), any(Duration.class));

        assertThat(service.recordFailedLogin(EMAIL, "10.0.0.1")).isEqualTo(5);
        verify(temporaryStore).put("login_block:" + EMAIL, "blocked", Duration.ofMinutes(15));
    }

    @Test
    void resetFailedLoginDeletesCounter() {
        service.resetFailedLogin(EMAIL);

        verify(temporaryStore).deleteCounter("login_fail:" + EMAIL);
    }

    @Test
    void otpRequestsLimitedToThreePerHourWindow() {
        when(temporaryStore.incrementWithinWindow("otp_rate:" + EMAIL, Duration.ofHours(1)))
                .thenReturn(3L, 4L);

        assertThat(service.recordOtpRequest(EMAIL)).isTrue();
        assertThat(service.recordOtpRequest(EMAIL)).isFalse();
    }

    @Test
    void storesResetOtpForFiveMinutes() {
        service.storeOtp(USER_ID, "123456");

        verify(temporaryStore).put("reset_otp:" + USER_ID, "123456", Duration.ofMinutes(5));
        assertThat(service.otpTtlSeconds()).isEqualTo(300);
    }

    @Test
    void verifyOtpConsumesCodeAndAttemptsOnMatch() {
        when(temporaryStore.incrementWithinWindow("otp_attempt:" + USER_ID, Duration.ofMinutes(5))).thenReturn(1L);
        when(temporaryStore.find("reset_otp:" + USER_ID)).thenReturn(Optional.of("123456"));

        assertThat(service.verifyOtp(USER_ID, "123456")).isTrue();

        verify(temporaryStore).delete("reset_otp:" + USER_ID);
        verify(temporaryStore).deleteCounter("otp_attempt:" + USER_ID);
    }

    @Test
    void checkOtpKeepsCodeOnMatchButStillCountsAttempt() {
        when(temporaryStore.incrementWithinWindow("otp_attempt:" + USER_ID, Duration.ofMinutes(5))).thenReturn(2L);
        when(temporaryStore.find("reset_otp:" + USER_ID)).thenReturn(Optional.of("123456"));

        assertThat(service.checkOtp(USER_ID, "123456")).isTrue();

        verify(temporaryStore, never()).delete(anyString());
        verify(temporaryStore, never()).deleteCounter(anyString());
    }

    @Test
    void wrongOrExpiredOtpIsRejected() {
        when(temporaryStore.incrementWithinWindow("otp_attempt:" + USER_ID, Duration.ofMinutes(5)))
                .thenReturn(1L, 2L);
        when(temporaryStore.find("reset_otp:" + USER_ID)).thenReturn(Optional.of("123456"), Optional.empty());

        assertThat(service.verifyOtp(USER_ID, "000000")).isFalse();
        assertThat(service.verifyOtp(USER_ID, "123456")).as("OTP đã hết hạn").isFalse();
    }

    @Test
    void sixthAttemptIsRejectedEvenWithCorrectCodeAndWipesOtp() {
        when(temporaryStore.incrementWithinWindow("reg_otp_attempt:" + EMAIL, Duration.ofMinutes(5))).thenReturn(6L);

        assertThat(service.verifyRegistrationOtp(EMAIL, "123456")).isFalse();

        verify(temporaryStore).delete("reg_otp:" + EMAIL);
        verify(temporaryStore).deleteCounter("reg_otp_attempt:" + EMAIL);
        verify(temporaryStore, never()).find(anyString());
    }

    @Test
    void registrationOtpUsesEmailKeysAndClearRemovesBoth() {
        service.storeRegistrationOtp(EMAIL, "654321");
        service.clearRegistrationOtp(EMAIL);

        verify(temporaryStore).put("reg_otp:" + EMAIL, "654321", Duration.ofMinutes(5));
        verify(temporaryStore).delete("reg_otp:" + EMAIL);
        verify(temporaryStore).deleteCounter("reg_otp_attempt:" + EMAIL);
    }
}
