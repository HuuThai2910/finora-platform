package com.finora.user.service.impl;

import com.finora.user.config.EkycManualReviewProperties;
import com.finora.user.service.EkycCounterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ngưỡng lỗi AI liên tiếp mở lối gửi nhân viên duyệt tay. */
@ExtendWith(MockitoExtension.class)
class EkycAiOutageServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private EkycCounterStore counterStore;

    private EkycManualReviewProperties properties;
    private EkycAiOutageServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new EkycManualReviewProperties();
        service = new EkycAiOutageServiceImpl(counterStore, properties);
    }

    @Test
    void defaultThresholdIsThreeConsecutiveFailuresWithinOneHour() {
        when(counterStore.recordAiFailure(USER_ID, Duration.ofHours(1))).thenReturn(1, 2, 3);

        assertThat(service.recordFailure(USER_ID)).isFalse();
        assertThat(service.recordFailure(USER_ID)).isFalse();
        assertThat(service.recordFailure(USER_ID)).as("lần lỗi thứ 3 mở lối duyệt tay").isTrue();
    }

    @Test
    void availabilityIsCheckedOnServerCounter() {
        when(counterStore.currentAiFailures(USER_ID)).thenReturn(2, 3);

        assertThat(service.isManualReviewAvailable(USER_ID)).isFalse();
        assertThat(service.isManualReviewAvailable(USER_ID)).isTrue();
    }

    @Test
    void configuredThresholdAndWindowAreUsedWithSafeMinimums() {
        properties.setAiFailureThreshold(0);
        properties.setAiFailureWindow(Duration.ofSeconds(5));
        when(counterStore.recordAiFailure(USER_ID, Duration.ofMinutes(1))).thenReturn(1);

        assertThat(service.recordFailure(USER_ID)).as("ngưỡng nhỏ hơn 1 được coi là 1").isTrue();
        assertThat(service.threshold()).isEqualTo(1);
    }

    @Test
    void successResetsCounterAndSwallowsStoreError() {
        service.recordSuccess(USER_ID);
        verify(counterStore).resetAiFailures(USER_ID);

        doThrow(new DataAccessResourceFailureException("down")).when(counterStore).resetAiFailures(USER_ID);
        assertThatCode(() -> service.recordSuccess(USER_ID)).doesNotThrowAnyException();
    }
}
