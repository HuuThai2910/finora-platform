package com.finora.user.service.impl;

import com.finora.user.client.AiLivenessClient.EvidenceFrame;
import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.integration.storage.EkycEvidenceStorage;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.service.EkycEvidenceService.BinaryFile;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.support.EkycTestData;
import com.finora.user.support.Sha256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Đặt tên object, băm nội dung và dọn file khi ghi dở dang. */
@ExtendWith(MockitoExtension.class)
class EkycEvidenceServiceImplTest {

    private static final UUID ATTEMPT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String BASE = "ekyc/dev/7/" + ATTEMPT + "/";

    @Mock
    private EkycEvidenceStorage storage;

    private EkycEvidenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EkycEvidenceServiceImpl(storage, new EkycStorageProperties());
    }

    @Test
    void cardImagesAreStoredPendingWithShaOfTheExactBytes() {
        List<StoredEvidence> stored = service.storeCardImages(7, ATTEMPT,
                new BinaryFile(EkycTestData.JPEG_BYTES, "image/jpeg", "jpg"),
                new BinaryFile(EkycTestData.PNG_BYTES, "image/png", "png"));

        assertThat(stored).extracting(StoredEvidence::objectKey)
                .containsExactly(BASE + "cccd-front.jpg", BASE + "cccd-back.png");
        assertThat(stored).extracting(StoredEvidence::kind)
                .containsExactly(EkycEvidenceKind.CCCD_FRONT, EkycEvidenceKind.CCCD_BACK);
        assertThat(stored.get(0).sha256()).isEqualTo(Sha256.hex(EkycTestData.JPEG_BYTES));
        assertThat(stored).allSatisfy(item -> assertThat(item.stage()).isEqualTo(EkycEvidenceStage.PENDING));
        verify(storage).put(BASE + "cccd-front.jpg", EkycTestData.JPEG_BYTES, "image/jpeg", EkycEvidenceStage.PENDING);
    }

    @Test
    void failureOnSecondImageDeletesTheFirst() {
        lenient().doThrow(new EkycStorageException("down")).when(storage)
                .put(eq(BASE + "cccd-back.jpg"), any(), any(), any());

        assertThatThrownBy(() -> service.storeCardImages(7, ATTEMPT,
                new BinaryFile(EkycTestData.JPEG_BYTES, "image/jpeg", "jpg"),
                new BinaryFile(EkycTestData.JPEG_BYTES, "image/jpeg", "jpg")))
                .isInstanceOf(EkycStorageException.class);
        verify(storage).delete(BASE + "cccd-front.jpg");
    }

    @Test
    void livenessRoundNamesVideoFramesAndStillsWithoutCollisions() {
        List<StoredEvidence> video = service.storeLiveness(7, ATTEMPT, 2,
                new BinaryFile(new byte[]{1}, "video/quicktime", "mov"), List.of(),
                List.of(new EvidenceFrame("FRONTAL", EkycTestData.JPEG_BASE64),
                        new EvidenceFrame("FRONTAL", EkycTestData.JPEG_BASE64),
                        new EvidenceFrame("TURN_LEFT", "không-phải-base64!!")));

        assertThat(video).extracting(StoredEvidence::objectKey).containsExactly(
                BASE + "liveness-2.mov",
                BASE + "liveness-frame-FRONTAL-2.jpg",
                BASE + "liveness-frame-FRONTAL_2-2.jpg");
        assertThat(video).extracting(StoredEvidence::label).containsExactly(null, "FRONTAL", "FRONTAL_2");

        List<StoredEvidence> stills = service.storeLiveness(7, ATTEMPT, 2, null,
                List.of(EkycTestData.JPEG_BYTES, EkycTestData.JPEG_BYTES), List.of());

        // Lần gửi thứ 2 đánh số tiếp sau 20 ảnh tối đa của lần 1.
        assertThat(stills).extracting(StoredEvidence::objectKey)
                .containsExactly(BASE + "still-21.jpg", BASE + "still-22.jpg");
        assertThat(stills).allSatisfy(item -> assertThat(item.kind()).isEqualTo(EkycEvidenceKind.LIVENESS_FRAME));
    }

    @Test
    void resultFileIsWrittenFinal() {
        StoredEvidence result = service.storeResult(7, ATTEMPT, "{}".getBytes());

        assertThat(result.objectKey()).isEqualTo(BASE + "result.json");
        assertThat(result.stage()).isEqualTo(EkycEvidenceStage.FINAL);
        assertThat(result.contentType()).isEqualTo("application/json");
    }

    @Test
    void markFinalCountsOnlyAcceptedTags() {
        when(storage.setStage("a", EkycEvidenceStage.FINAL)).thenReturn(true);
        when(storage.setStage("b", EkycEvidenceStage.FINAL)).thenReturn(false);

        assertThat(service.markFinal(List.of("a", "b"))).isEqualTo(1);
    }

    @Test
    void deleteObjectsReportsOnlySuccessfulKeys() {
        lenient().doThrow(new EkycStorageException("down")).when(storage).delete("b");

        assertThat(service.deleteObjects(List.of("a", "b", "c"))).containsExactly("a", "c");
    }

    @Test
    void userDeletionUsesUserPrefix() {
        assertThat(service.deleteUserObjects(7)).isTrue();
        verify(storage).deletePrefix("ekyc/dev/7/");

        when(storage.deletePrefix("ekyc/dev/8/")).thenThrow(new EkycStorageException("down"));
        assertThat(service.deleteUserObjects(8)).isFalse();
    }
}
