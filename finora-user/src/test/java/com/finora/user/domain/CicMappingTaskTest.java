package com.finora.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CicMappingTaskTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    void taskHetLeaseDuocClaimLaiSauKhiWorkerCuBiDung() {
        CicMappingTask task = CicMappingTask.pending(7L, NOW);

        task.claim(NOW, Duration.ofMinutes(2));

        assertThat(task.getStatus()).isEqualTo(CicMappingTaskStatus.PROCESSING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.isDue(NOW.plusSeconds(119))).isFalse();
        assertThat(task.isDue(NOW.plusSeconds(120))).isTrue();

        task.claim(NOW.plusSeconds(120), Duration.ofMinutes(2));
        assertThat(task.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void loiTamThoiHenRetryConLoiDuLieuChuyenDead() {
        CicMappingTask retryable = CicMappingTask.pending(7L, NOW);
        retryable.claim(NOW, Duration.ofMinutes(2));
        retryable.fail("CIC_HTTP_503", true, 3, Duration.ofSeconds(30), NOW);

        assertThat(retryable.getStatus()).isEqualTo(CicMappingTaskStatus.RETRY_PENDING);
        assertThat(retryable.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));

        CicMappingTask permanent = CicMappingTask.pending(8L, NOW);
        permanent.claim(NOW, Duration.ofMinutes(2));
        permanent.fail("PROFILE_IDENTITY_MISSING", false, 3, Duration.ofSeconds(30), NOW);

        assertThat(permanent.getStatus()).isEqualTo(CicMappingTaskStatus.DEAD);
    }

    @Test
    void khongTheCompleteKhiChuaClaim() {
        CicMappingTask task = CicMappingTask.pending(7L, NOW);

        assertThatThrownBy(() -> task.complete(NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
