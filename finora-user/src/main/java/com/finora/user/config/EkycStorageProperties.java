package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình kho lưu bằng chứng eKYC qua S3 API ({@code finora.ekyc.storage.*}).
 * <p>
 * Một bản code cho hai môi trường: dev local dùng SeaweedFS (đặt {@link #endpoint} + {@link #pathStyle}),
 * deploy dùng Amazon S3 (bỏ trống hai giá trị đó). {@link #enabled} mặc định {@code false}: khi chưa có
 * kho lưu thì eKYC dừng ở {@code STORAGE_UNAVAILABLE} chứ không bao giờ xác minh mà thiếu bằng chứng.
 * Khoá truy cập chỉ đọc từ biến môi trường, không đưa vào repo.
 */
@ConfigurationProperties(prefix = "finora.ekyc.storage")
@Getter
@Setter
public class EkycStorageProperties {

    private boolean enabled = false;

    /** Endpoint S3-compatible (ví dụ {@code http://localhost:9100}); trống = Amazon S3 theo region. */
    private String endpoint = "";

    private String region = "us-east-1";

    private String bucket = "finora-ekyc";

    /** SeaweedFS cần path-style ({@code http://host/bucket/key}); Amazon S3 dùng virtual-hosted. */
    private boolean pathStyle = false;

    private String accessKey = "";

    private String secretKey = "";

    /** Đoạn môi trường trong object key {@code ekyc/{env}/…}; chỉ chữ thường, số và dấu gạch. */
    private String env = "dev";

    /** Số ngày giữ bằng chứng của lượt đã có kết quả cuối, sau đó worker tự xoá. */
    private int retentionDays = 30;
}
