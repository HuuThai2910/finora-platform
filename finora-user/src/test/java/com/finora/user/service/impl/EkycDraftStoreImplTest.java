package com.finora.user.service.impl;

import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycLivenessResult;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.service.TemporaryStore;
import com.finora.user.util.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bản nháp eKYC trong kho tạm phải được mã hoá và đọc lại nguyên vẹn. */
@ExtendWith(MockitoExtension.class)
class EkycDraftStoreImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private TemporaryStore temporaryStore;

    private EkycDraftStoreImpl store;

    @BeforeEach
    void setUp() {
        store = new EkycDraftStoreImpl(temporaryStore, EkycTestData.objectMapper(), EkycTestData.cryptoProperties());
    }

    @Test
    void savesEncryptedWithFifteenMinuteTtlAndReadsBackIdentically() {
        UUID attemptId = UUID.randomUUID();
        EkycIdentityDraft draft = EkycTestData.draft(attemptId, EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);

        store.save(USER_ID, draft);

        verify(temporaryStore).put(eq("ekyc_draft:" + USER_ID), stored.capture(), eq(Duration.ofMinutes(15)));
        assertThat(stored.getValue()).as("Bảng tạm không được thấy số định danh hay embedding dạng rõ")
                .doesNotContain(EkycTestData.ID_NUMBER, EkycTestData.EMBEDDING, "NGUYỄN");

        when(temporaryStore.find("ekyc_draft:" + USER_ID)).thenReturn(Optional.of(stored.getValue()));
        Optional<EkycIdentityDraft> loaded = store.find(USER_ID);

        assertThat(loaded).contains(draft);
    }

    @Test
    void unreadableValueIsTreatedAsExpired() {
        when(temporaryStore.find("ekyc_draft:" + USER_ID)).thenReturn(Optional.of("không-phải-bản-mã"));

        assertThat(store.find(USER_ID)).isEmpty();
    }

    @Test
    void missingOrExpiredDraftIsEmptyAndRemoveDeletesKey() {
        when(temporaryStore.find("ekyc_draft:" + USER_ID)).thenReturn(Optional.empty());

        assertThat(store.find(USER_ID)).isEmpty();
        store.remove(USER_ID);

        verify(temporaryStore).delete("ekyc_draft:" + USER_ID);
    }
}
