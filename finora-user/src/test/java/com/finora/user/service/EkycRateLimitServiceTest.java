package com.finora.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Kiểm tra suất gọi xác minh eKYC trên Redis. */
@ExtendWith(MockitoExtension.class)
class EkycRateLimitServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EkycRateLimitService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new EkycRateLimitService(redisTemplate);
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
}
