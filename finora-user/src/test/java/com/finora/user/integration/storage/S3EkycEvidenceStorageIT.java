package com.finora.user.integration.storage;

import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycEvidenceStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chạy thật với kho S3-compatible (SeaweedFS local). Mặc định bị bỏ qua; bật bằng biến môi trường:
 * <pre>
 * EKYC_S3_IT_ENDPOINT=http://127.0.0.1:9100 EKYC_S3_IT_ACCESS_KEY=… EKYC_S3_IT_SECRET_KEY=…
 * [EKYC_S3_IT_BUCKET=finora-ekyc] mvn -pl finora-user verify -Dit.test=S3EkycEvidenceStorageIT
 * </pre>
 * Test chỉ ghi dưới tiền tố {@code ekyc/it-<ngẫu nhiên>/} và tự xoá sạch khi xong.
 */
@EnabledIfEnvironmentVariable(named = "EKYC_S3_IT_ENDPOINT", matches = ".+")
class S3EkycEvidenceStorageIT {

    private S3Client client;
    private S3EkycEvidenceStorage storage;
    private String prefix;
    private String bucket;

    @BeforeEach
    void setUp() {
        EkycStorageProperties properties = new EkycStorageProperties();
        properties.setEnabled(true);
        properties.setEndpoint(System.getenv("EKYC_S3_IT_ENDPOINT"));
        properties.setPathStyle(true);
        properties.setRegion("us-east-1");
        properties.setAccessKey(System.getenv("EKYC_S3_IT_ACCESS_KEY"));
        properties.setSecretKey(System.getenv("EKYC_S3_IT_SECRET_KEY"));
        bucket = System.getenv().getOrDefault("EKYC_S3_IT_BUCKET", "finora-ekyc");
        client = S3EkycEvidenceStorage.buildClient(properties);
        storage = new S3EkycEvidenceStorage(client, bucket);
        prefix = "ekyc/it-" + UUID.randomUUID() + "/";
    }

    @AfterEach
    void tearDown() {
        storage.deletePrefix(prefix);
        storage.close();
    }

    @Test
    void putTagReadRetagAndDeleteByPrefix() throws IOException {
        String key = prefix + "7/attempt/cccd-front.jpg";
        byte[] content = "ảnh-thẻ-giả".getBytes(StandardCharsets.UTF_8);

        storage.put(key, content, "image/jpeg", EkycEvidenceStage.PENDING);
        assertThat(stageTag(key)).isEqualTo("pending");
        assertThat(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentType())
                .isEqualTo("image/jpeg");

        try (StoredObject object = storage.open(key)) {
            assertThat(object.content().readAllBytes()).isEqualTo(content);
            assertThat(object.contentLength()).isEqualTo(content.length);
        }

        assertThat(storage.setStage(key, EkycEvidenceStage.FINAL)).isTrue();
        assertThat(stageTag(key)).isEqualTo("final");

        storage.put(prefix + "7/attempt/result.json", "{}".getBytes(StandardCharsets.UTF_8), "application/json",
                EkycEvidenceStage.FINAL);
        assertThat(storage.deletePrefix(prefix + "7/")).isEqualTo(2);
        assertThatThrownBy(() -> storage.open(key))
                .isInstanceOfSatisfying(EkycStorageException.class, e -> assertThat(e.isNotFound()).isTrue());
        storage.delete(key);
    }

    private String stageTag(String key) {
        return client.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucket).key(key).build())
                .tagSet().stream()
                .filter(tag -> "stage".equals(tag.key()))
                .map(Tag::value)
                .findFirst()
                .orElse(null);
    }
}
