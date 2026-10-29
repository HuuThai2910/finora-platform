package com.finora.user.integration.storage;

import com.finora.user.domain.EkycEvidenceStage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quy ước object key không chứa PII và kho tắt luôn báo lỗi khi ghi/đọc. */
class EkycStorageKeysTest {

    private static final UUID ATTEMPT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void buildsKeysUnderEnvironmentUserAndAttempt() {
        assertThat(EkycStorageKeys.objectKey("dev", 7, ATTEMPT, EkycStorageKeys.cardImageFile("cccd-front", "jpg")))
                .isEqualTo("ekyc/dev/7/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/cccd-front.jpg");
        assertThat(EkycStorageKeys.userPrefix("prod", 42)).isEqualTo("ekyc/prod/42/");
        assertThat(EkycStorageKeys.envPrefix("dev")).isEqualTo("ekyc/dev/");
        assertThat(EkycStorageKeys.livenessVideoFile(2, "mov")).isEqualTo("liveness-2.mov");
        assertThat(EkycStorageKeys.livenessFrameFile("turn left!", 1)).isEqualTo("liveness-frame-TURN_LEFT_-1.jpg");
        assertThat(EkycStorageKeys.stillFile(21)).isEqualTo("still-21.jpg");
        assertThat(EkycStorageKeys.RESULT_FILE).isEqualTo("result.json");
    }

    @Test
    void rejectsEnvironmentThatCouldBreakThePrefix() {
        assertThat(EkycStorageKeys.requireValidEnv("dev-01")).isEqualTo("dev-01");
        assertThatThrownBy(() -> EkycStorageKeys.requireValidEnv("../prod")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EkycStorageKeys.requireValidEnv("Dev")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EkycStorageKeys.requireValidEnv("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledStorageFailsWritesAndReadsButAllowsDeletes() {
        DisabledEkycEvidenceStorage storage = new DisabledEkycEvidenceStorage();

        assertThat(storage.isEnabled()).isFalse();
        assertThatThrownBy(() -> storage.put("k", new byte[]{1}, "image/jpeg", EkycEvidenceStage.PENDING))
                .isInstanceOf(EkycStorageException.class);
        assertThatThrownBy(() -> storage.open("k")).isInstanceOf(EkycStorageException.class);
        assertThat(storage.setStage("k", EkycEvidenceStage.FINAL)).isFalse();
        storage.delete("k");
        assertThat(storage.deletePrefix("ekyc/dev/7/")).isZero();
    }
}
