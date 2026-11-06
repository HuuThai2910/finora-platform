package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Worker dọn kho tạm: gọi xoá dòng hết hạn, lỗi database không làm vỡ lịch chạy. */
@ExtendWith(MockitoExtension.class)
class TemporaryStoreCleanupWorkerImplTest {

    @Mock private TemporaryStore temporaryStore;

    @InjectMocks private TemporaryStoreCleanupWorkerImpl worker;

    @Test
    void deletesExpiredRows() {
        when(temporaryStore.deleteExpired()).thenReturn(3);

        worker.run();

        verify(temporaryStore).deleteExpired();
    }

    @Test
    void databaseErrorIsLoggedNotThrown() {
        when(temporaryStore.deleteExpired()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(worker::run).doesNotThrowAnyException();
    }
}
