package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.user.domain.model.PendingRegistration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phiên đăng ký tạm: TTL 5 phút như OTP, mật khẩu chỉ nằm trong kho tạm ở dạng mã hoá. */
@ExtendWith(MockitoExtension.class)
class PendingRegistrationStoreImplTest {

    private static final String EMAIL = "nguoi.vay@example.com";
    private static final String PASSWORD = "MatKhau@123";

    @Mock private TemporaryStore temporaryStore;

    private PendingRegistrationStoreImpl store;

    @BeforeEach
    void setUp() {
        store = new PendingRegistrationStoreImpl(temporaryStore, EkycTestData.cryptoProperties(),
                EkycTestData.objectMapper());
    }

    @Test
    void savesEncryptedPasswordWithFiveMinuteTtlAndReadsBackPlain() {
        PendingRegistration registration = new PendingRegistration(
                EMAIL, PASSWORD, "Nguyễn Văn A", "0912345678", UserRole.BORROWER);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);

        store.save(registration);

        verify(temporaryStore).put(eq("pending_reg:" + EMAIL), stored.capture(), eq(Duration.ofMinutes(5)));
        assertThat(stored.getValue()).as("mật khẩu không được nằm dạng rõ").doesNotContain(PASSWORD);

        when(temporaryStore.find("pending_reg:" + EMAIL)).thenReturn(Optional.of(stored.getValue()));
        assertThat(store.find(EMAIL)).contains(registration);
        assertThat(store.ttlSeconds()).isEqualTo(300);
    }

    @Test
    void missingOrExpiredRegistrationIsEmpty() {
        when(temporaryStore.find("pending_reg:" + EMAIL)).thenReturn(Optional.empty());

        assertThat(store.find(EMAIL)).isEmpty();
    }

    @Test
    void unreadableRecordIsDeletedAndTreatedAsMissing() {
        when(temporaryStore.find("pending_reg:" + EMAIL)).thenReturn(Optional.of("không-phải-json"));

        assertThat(store.find(EMAIL)).isEmpty();
        verify(temporaryStore).delete("pending_reg:" + EMAIL);
    }
}
