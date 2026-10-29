package com.finora.user.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra các suất gọi eKYC trên Redis. */
@ExtendWith(MockitoExtension.class)
class EkycRateLimitServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EkycRateLimitServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new EkycRateLimitServiceImpl(redisTemplate);
    }

    @Test
    void chiemDuocSuatKhiChuaCoLanGoiTruoc() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isTrue();
    }

    @Test
    void goiQuaNhanhThiKhongChiemDuocSuat() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isFalse();
    }

    @Test
    void traLaiSuatQuetTheKhiAiLoi() {
        service.releaseVerifySlot(USER_ID);

        verify(redisTemplate).delete("ekyc_verify_rate:" + USER_ID);
    }

    @Test
    void livenessVaThuThachCoKhoaVaKhoangChoRieng() {
        when(valueOperations.setIfAbsent("ekyc_liveness_rate:" + USER_ID, "1", Duration.ofSeconds(10))).thenReturn(true);
        when(valueOperations.setIfAbsent("ekyc_challenge_rate:" + USER_ID, "1", Duration.ofSeconds(3))).thenReturn(true);

        assertThat(service.tryAcquireLivenessSlot(USER_ID)).isTrue();
        assertThat(service.tryAcquireChallengeSlot(USER_ID)).isTrue();
        service.releaseLivenessSlot(USER_ID);

        verify(redisTemplate).delete("ekyc_liveness_rate:" + USER_ID);
    }
}
