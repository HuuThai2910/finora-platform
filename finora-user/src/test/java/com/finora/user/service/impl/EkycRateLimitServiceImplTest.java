package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra các suất gọi eKYC trong kho tạm. */
@ExtendWith(MockitoExtension.class)
class EkycRateLimitServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private TemporaryStore temporaryStore;

    private EkycRateLimitServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EkycRateLimitServiceImpl(temporaryStore);
    }

    @Test
    void chiemDuocSuatKhiChuaCoLanGoiTruoc() {
        when(temporaryStore.putIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isTrue();
        verify(temporaryStore).putIfAbsent("ekyc_verify_rate:" + USER_ID, "1", Duration.ofSeconds(10));
    }

    @Test
    void goiQuaNhanhThiKhongChiemDuocSuat() {
        when(temporaryStore.putIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isFalse();
    }

    @Test
    void traLaiSuatQuetTheKhiAiLoi() {
        service.releaseVerifySlot(USER_ID);

        verify(temporaryStore).delete("ekyc_verify_rate:" + USER_ID);
    }

    @Test
    void livenessVaThuThachCoKhoaVaKhoangChoRieng() {
        when(temporaryStore.putIfAbsent("ekyc_liveness_rate:" + USER_ID, "1", Duration.ofSeconds(10))).thenReturn(true);
        when(temporaryStore.putIfAbsent("ekyc_challenge_rate:" + USER_ID, "1", Duration.ofSeconds(3))).thenReturn(true);

        assertThat(service.tryAcquireLivenessSlot(USER_ID)).isTrue();
        assertThat(service.tryAcquireChallengeSlot(USER_ID)).isTrue();
        service.releaseLivenessSlot(USER_ID);

        verify(temporaryStore).delete("ekyc_liveness_rate:" + USER_ID);
    }

    @Test
    void loiDatabaseKhongDuocChoQuaGioiHan() {
        when(temporaryStore.putIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.tryAcquireVerifySlot(USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
