package com.finora.investment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker lập giấy xác nhận chuyển nhượng Note và con dấu số FINORA.
 *
 * <p>Không cấu hình {@code seal.keystore-path} thì con dấu là chứng thư demo tự ký sinh lúc khởi động (ghi rõ không
 * có giá trị pháp lý). Có keystore PKCS#12 thì dùng khóa và chuỗi chứng thư trong đó.</p>
 */
@ConfigurationProperties(prefix = "finora.investment.transfer-document")
public record TransferDocumentProperties(
        boolean enabled,
        Duration delay,
        int batchSize,
        int maxAttempts,
        Duration retryBackoff,
        Duration maximumBackoff,
        Duration processingLease,
        Seal seal
) {
    public TransferDocumentProperties {
        delay = delay == null ? Duration.ofSeconds(10) : delay;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
        maximumBackoff = maximumBackoff == null ? Duration.ofMinutes(30) : maximumBackoff;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        batchSize = batchSize == 0 ? 20 : batchSize;
        maxAttempts = maxAttempts == 0 ? 8 : maxAttempts;
        seal = seal == null ? new Seal(null, null, null) : seal;
        if (batchSize < 1 || batchSize > 200 || maxAttempts < 1) {
            throw new IllegalArgumentException("Cấu hình giấy xác nhận chuyển nhượng không hợp lệ");
        }
    }

    /**
     * @param keystorePath     tệp PKCS#12 chứa khóa con dấu; bỏ trống là dùng chứng thư demo
     * @param keystorePassword mật khẩu keystore (cũng là mật khẩu khóa)
     * @param keyAlias         alias của khóa; bỏ trống là lấy alias khóa đầu tiên
     */
    public record Seal(String keystorePath, String keystorePassword, String keyAlias) {

        public boolean configured() {
            return keystorePath != null && !keystorePath.isBlank();
        }
    }
}
