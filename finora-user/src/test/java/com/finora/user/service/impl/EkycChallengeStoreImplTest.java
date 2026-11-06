package com.finora.user.service.impl;

import com.finora.user.domain.enums.LivenessAction;
import com.finora.user.domain.model.LivenessStep;
import com.finora.user.service.EkycChallengeStore.StoredChallenge;
import com.finora.user.service.TemporaryStore;
import com.finora.user.util.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phiên thử thách liveness: TTL 90 giây, mỗi người một phiên, lấy ra đúng một lần. */
@ExtendWith(MockitoExtension.class)
class EkycChallengeStoreImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private TemporaryStore temporaryStore;

    private EkycChallengeStoreImpl store;

    @BeforeEach
    void setUp() {
        store = new EkycChallengeStoreImpl(temporaryStore, EkycTestData.objectMapper());
    }

    @Test
    void savesWithNinetySecondTtlAndConsumesOnceByTake() {
        StoredChallenge challenge = new StoredChallenge(UUID.randomUUID(),
                List.of(new LivenessStep(LivenessAction.TURN_LEFT, 800)), UUID.randomUUID(), "2026-11-04T01:02:03Z");
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);

        store.save(USER_ID, challenge);

        verify(temporaryStore).put(eq("ekyc_challenge:" + USER_ID), stored.capture(), eq(Duration.ofSeconds(90)));

        when(temporaryStore.take("ekyc_challenge:" + USER_ID)).thenReturn(Optional.of(stored.getValue()));
        assertThat(store.consume(USER_ID)).contains(challenge);
        // Lấy ra bằng take (lấy và xoá cùng lúc), không đọc rồi xoá riêng.
        verify(temporaryStore, never()).find(anyString());
    }

    @Test
    void missingOrCorruptChallengeIsEmpty() {
        when(temporaryStore.take("ekyc_challenge:" + USER_ID)).thenReturn(Optional.empty());
        assertThat(store.consume(USER_ID)).isEmpty();

        when(temporaryStore.take("ekyc_challenge:" + USER_ID)).thenReturn(Optional.of("{hỏng"));
        assertThat(store.consume(USER_ID)).isEmpty();
    }

    @Test
    void removeDeletesKey() {
        store.remove(USER_ID);

        verify(temporaryStore).delete("ekyc_challenge:" + USER_ID);
    }
}
