package com.finora.user.integration.storage;

import com.finora.user.domain.EkycEvidenceStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hành vi của bản cài đặt S3 với client giả: tag là best-effort (EKYC-002 sau khi đổi sang SeaweedFS),
 * còn ghi/đọc/xoá lỗi thật thì phải báo lỗi.
 */
@ExtendWith(MockitoExtension.class)
class S3EkycEvidenceStorageTest {

    @Mock
    private S3Client s3;

    private S3EkycEvidenceStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3EkycEvidenceStorage(s3, "finora-ekyc");
    }

    @Test
    void putSendsStageTagContentTypeAndLength() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());

        storage.put("ekyc/dev/7/a/cccd-front.jpg", new byte[]{1, 2, 3}, "image/jpeg", EkycEvidenceStage.PENDING);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("finora-ekyc");
        assertThat(request.getValue().tagging()).isEqualTo("stage=pending");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().contentLength()).isEqualTo(3L);
    }

    @Test
    void putRetriesWithoutTagWhenStoreRejectsTagging() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(501))
                .thenReturn(PutObjectResponse.builder().build());

        storage.put("k", new byte[]{1}, "image/jpeg", EkycEvidenceStage.PENDING);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, times(2)).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getAllValues().get(0).tagging()).isEqualTo("stage=pending");
        assertThat(request.getAllValues().get(1).tagging()).isNull();
    }

    @Test
    void putFailureIsStorageException() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(s3Error(500));

        assertThatThrownBy(() -> storage.put("k", new byte[]{1}, "image/jpeg", EkycEvidenceStage.PENDING))
                .isInstanceOf(EkycStorageException.class)
                .hasMessageContaining("status=500");
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void putNetworkFailureIsStorageException() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connect timed out"));

        assertThatThrownBy(() -> storage.put("k", new byte[]{1}, "image/jpeg", EkycEvidenceStage.PENDING))
                .isInstanceOf(EkycStorageException.class);
    }

    @Test
    void setStageIsBestEffort() {
        when(s3.putObjectTagging(any(PutObjectTaggingRequest.class))).thenThrow(s3Error(501));

        assertThat(storage.setStage("k", EkycEvidenceStage.FINAL)).isFalse();
    }

    @Test
    void setStageWritesFinalTag() {
        assertThat(storage.setStage("k", EkycEvidenceStage.FINAL)).isTrue();

        ArgumentCaptor<PutObjectTaggingRequest> request = ArgumentCaptor.forClass(PutObjectTaggingRequest.class);
        verify(s3).putObjectTagging(request.capture());
        assertThat(request.getValue().tagging().tagSet()).singleElement().satisfies(tag -> {
            assertThat(tag.key()).isEqualTo("stage");
            assertThat(tag.value()).isEqualTo("final");
        });
    }

    @Test
    void missingObjectIsReportedAsNotFound() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThatThrownBy(() -> storage.open("k"))
                .isInstanceOfSatisfying(EkycStorageException.class, e -> assertThat(e.isNotFound()).isTrue());
    }

    @Test
    void deleteFailureIsStorageException() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(503));

        assertThatThrownBy(() -> storage.delete("k")).isInstanceOf(EkycStorageException.class);
    }

    private static S3Exception s3Error(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("error " + status).build();
    }
}
