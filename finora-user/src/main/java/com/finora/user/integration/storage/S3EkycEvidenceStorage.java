package com.finora.user.integration.storage;

import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycEvidenceStage;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lưu bằng chứng eKYC bằng AWS SDK for Java v2 — cùng một bản code cho SeaweedFS (local) và Amazon S3.
 * <p>
 * Với SeaweedFS: đặt {@code endpoint} + path-style, đồng thời chỉ tính checksum khi thao tác bắt buộc
 * ({@code WHEN_REQUIRED}); từ SDK 2.30, mặc định gửi checksum CRC32 dạng trailer mà kho S3-compatible
 * không phải bản nào cũng hiểu. Mã hoá khi lưu do kho đảm nhiệm ({@code -s3.encryptVolumeData} ở
 * SeaweedFS, SSE-S3 mặc định ở Amazon S3) nên client không gửi header SSE.
 * <p>
 * Retry: chế độ STANDARD của SDK — tối đa 3 lần gọi (thử lại 2 lần) cho lỗi tạm thời; timeout mỗi lần
 * 20 giây, cả lời gọi 60 giây để video 10 MB vẫn kịp đi qua mạng chậm.
 */
@Slf4j
public class S3EkycEvidenceStorage implements EkycEvidenceStorage, AutoCloseable {

    static final String STAGE_TAG_KEY = "stage";

    private final S3Client s3;
    private final String bucket;

    public S3EkycEvidenceStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** Dựng S3 client theo cấu hình; không gọi mạng lúc khởi tạo. */
    public static S3Client buildClient(EkycStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(30))
                        .maxConnections(16))
                .overrideConfiguration(override -> override
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallAttemptTimeout(Duration.ofSeconds(20))
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .forcePathStyle(properties.isPathStyle());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint().trim()))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(EkycStorageProperties properties) {
        if (isBlank(properties.getAccessKey()) || isBlank(properties.getSecretKey())) {
            // Trên AWS có thể dùng IAM role của máy; local thì phải đặt khoá trong .env.
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey().trim(), properties.getSecretKey().trim()));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void put(String key, byte[] content, String contentType, EkycEvidenceStage stage) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength((long) content.length);
        try {
            s3.putObject(request.tagging(STAGE_TAG_KEY + "=" + stage.value()).build(), RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            if (!isTaggingRejection(e)) {
                throw failure("PutObject", key, e);
            }
            // Kho không nhận tag kèm PutObject (thiếu quyền Tagging, chưa hỗ trợ…): ghi object không tag.
            // Hạn lưu vẫn được bảo đảm bởi cột stage + worker quét dọn, nên không làm hỏng luồng người dùng.
            log.warn("Kho từ chối tag khi ghi bằng chứng, ghi lại không kèm tag: key={}, status={}, code={}",
                    key, e.statusCode(), errorCode(e));
            try {
                s3.putObject(request.tagging((String) null).build(), RequestBody.fromBytes(content));
            } catch (SdkException retryFailure) {
                throw failure("PutObject", key, retryFailure);
            }
        } catch (SdkException e) {
            throw failure("PutObject", key, e);
        }
    }

    @Override
    public StoredObject open(String key) {
        try {
            ResponseInputStream<GetObjectResponse> stream = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            GetObjectResponse response = stream.response();
            long length = response.contentLength() == null ? -1 : response.contentLength();
            return new StoredObject(stream, length, response.contentType());
        } catch (NoSuchKeyException e) {
            throw new EkycStorageException("GetObject: object không tồn tại", true, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new EkycStorageException("GetObject: object không tồn tại", true, e);
            }
            throw failure("GetObject", key, e);
        } catch (SdkException e) {
            throw failure("GetObject", key, e);
        }
    }

    @Override
    public boolean setStage(String key, EkycEvidenceStage stage) {
        try {
            s3.putObjectTagging(PutObjectTaggingRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .tagging(Tagging.builder()
                            .tagSet(Tag.builder().key(STAGE_TAG_KEY).value(stage.value()).build())
                            .build())
                    .build());
            return true;
        } catch (SdkException e) {
            log.warn("Không gắn được tag giai đoạn cho bằng chứng, giữ nguyên theo cột stage: key={}, stage={}, cause={}",
                    key, stage.value(), e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            // Đã không còn: coi như xoá xong.
        } catch (SdkException e) {
            throw failure("DeleteObject", key, e);
        }
    }

    /**
     * Xoá theo tiền tố bằng từng lệnh DeleteObject thay vì DeleteObjects hàng loạt: mỗi người dùng chỉ có
     * vài chục object, còn DeleteObjects bắt buộc checksum nội dung mà không phải kho S3-compatible nào
     * cũng kiểm giống Amazon S3.
     */
    @Override
    public int deletePrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            for (S3Object object : s3.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).contents()) {
                keys.add(object.key());
            }
        } catch (SdkException e) {
            throw failure("ListObjectsV2", prefix, e);
        }
        for (String key : keys) {
            delete(key);
        }
        return keys.size();
    }

    @Override
    public void close() {
        s3.close();
    }

    /** Lỗi 400/403/501 khi ghi kèm tag thường là do tag (quyền Tagging, kho chưa hỗ trợ), không phải do object. */
    private static boolean isTaggingRejection(S3Exception e) {
        int status = e.statusCode();
        return status == 400 || status == 403 || status == 501;
    }

    private static EkycStorageException failure(String operation, String key, SdkException cause) {
        String detail = cause instanceof S3Exception s3Exception
                ? "status=" + s3Exception.statusCode() + ", code=" + errorCode(s3Exception)
                : "cause=" + cause.getClass().getSimpleName();
        return new EkycStorageException(operation + " thất bại (" + detail + ") key=" + key, false, cause);
    }

    private static String errorCode(S3Exception e) {
        return e.awsErrorDetails() == null ? "?" : e.awsErrorDetails().errorCode();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
