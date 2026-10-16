package com.finora.user.service;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bộ đếm chụp lại, trượt liveness và chống phát lại video trên Redis. */
@ExtendWith(MockitoExtension.class)
class EkycCounterStoreTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private EkycCounterStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new EkycCounterStore(redisTemplate);
    }

    @Test
    void retakeCountDefaultsToZeroAndRefreshesOneHourTtl() {
        when(valueOperations.get("ekyc_retake_count:" + USER_ID)).thenReturn(null);
        assertThat(store.currentRetakeCount(USER_ID)).isZero();

        store.incrementRetakeCount(USER_ID);

        verify(valueOperations).increment("ekyc_retake_count:" + USER_ID);
        verify(redisTemplate).expire("ekyc_retake_count:" + USER_ID, Duration.ofHours(1));
    }

    @Test
    void livenessFailureWindowStartsAtFirstFailure() {
        when(valueOperations.increment("ekyc_liveness_fail:" + USER_ID)).thenReturn(1L, 2L);

        assertThat(store.recordLivenessFailure(USER_ID)).isEqualTo(1);
        assertThat(store.recordLivenessFailure(USER_ID)).isEqualTo(2);

        // Chỉ đặt TTL 24 giờ ở lần trượt đầu, lần sau không kéo dài cửa sổ.
        verify(redisTemplate).expire("ekyc_liveness_fail:" + USER_ID, Duration.ofHours(24));
    }

    @Test
    void mediaRegistrationIsAtomicSetIfAbsentForThirtyDays() {
        when(valueOperations.setIfAbsent("ekyc_media_sha:abc", "1", Duration.ofDays(30))).thenReturn(true, false);

        assertThat(store.registerMedia("abc")).isTrue();
        assertThat(store.registerMedia("abc")).as("gửi lại đúng file là phát lại").isFalse();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
