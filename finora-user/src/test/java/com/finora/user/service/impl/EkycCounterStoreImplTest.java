package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bộ đếm chụp lại, trượt liveness và chống phát lại video trong kho tạm. */
@ExtendWith(MockitoExtension.class)
class EkycCounterStoreImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private TemporaryStore temporaryStore;

    private EkycCounterStoreImpl store;

    @BeforeEach
    void setUp() {
        store = new EkycCounterStoreImpl(temporaryStore);
    }

    @Test
    void retakeCountDefaultsToZeroAndRefreshesOneHourTtl() {
        when(temporaryStore.findCounter("ekyc_retake_count:" + USER_ID)).thenReturn(0L);
        assertThat(store.currentRetakeCount(USER_ID)).isZero();

        store.incrementRetakeCount(USER_ID);

        // TTL làm mới mỗi lần tăng, không phải cửa sổ cố định.
        verify(temporaryStore).incrementRefreshingTtl("ekyc_retake_count:" + USER_ID, Duration.ofHours(1));
        verify(temporaryStore, never()).incrementWithinWindow(anyString(), any(Duration.class));
    }

    @Test
    void retakeCountReadsStoredCounter() {
        when(temporaryStore.findCounter("ekyc_retake_count:" + USER_ID)).thenReturn(2L);

        assertThat(store.currentRetakeCount(USER_ID)).isEqualTo(2);
    }

    @Test
    void livenessFailureWindowStartsAtFirstFailure() {
        when(temporaryStore.incrementWithinWindow("ekyc_liveness_fail:" + USER_ID, Duration.ofHours(24)))
                .thenReturn(1L, 2L);

        assertThat(store.recordLivenessFailure(USER_ID)).isEqualTo(1);
        assertThat(store.recordLivenessFailure(USER_ID)).isEqualTo(2);

        // Cửa sổ 24 giờ cố định từ lần trượt đầu, lần sau không kéo dài.
        verify(temporaryStore, never()).incrementRefreshingTtl(anyString(), any(Duration.class));
    }

    @Test
    void resetsDeleteCounters() {
        store.resetRetakeCount(USER_ID);
        store.resetLivenessFailures(USER_ID);

        verify(temporaryStore).deleteCounter("ekyc_retake_count:" + USER_ID);
        verify(temporaryStore).deleteCounter("ekyc_liveness_fail:" + USER_ID);
    }

    @Test
    void mediaRegistrationIsAtomicSetIfAbsentForThirtyDays() {
        when(temporaryStore.putIfAbsent("ekyc_media_sha:abc", "1", Duration.ofDays(30))).thenReturn(true, false);

        assertThat(store.registerMedia("abc")).isTrue();
        assertThat(store.registerMedia("abc")).as("gửi lại đúng file là phát lại").isFalse();
        verify(temporaryStore, never()).put(anyString(), anyString(), any(Duration.class));
    }
}
