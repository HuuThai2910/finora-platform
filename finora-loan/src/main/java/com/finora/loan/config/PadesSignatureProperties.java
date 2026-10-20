package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình ký PAdES nhiều bên (R5, D13). Không chứa secret.
 *
 * @param enabled               bật PAdES cho hợp đồng nhiều bên mới; tắt thì hợp đồng mới quay lại bằng chứng tách rời
 *                              (đường lùi vận hành nếu ký hash SmartCA UAT không tương thích), hợp đồng đã lập giữ nguyên
 * @param signingSessionTimeout phiên ký đang chờ provider quá hạn này thì được giải phóng cho bên khác; SmartCA hết hạn
 *                              giao dịch sau khoảng 300 giây nên mặc định 10 phút chừa biên an toàn
 * @param workerBatchSize       số phiên đang chờ worker đối soát mỗi lượt
 */
@ConfigurationProperties(prefix = "finora.signature.pades")
public record PadesSignatureProperties(
        Boolean enabled,
        Duration signingSessionTimeout,
        Integer workerBatchSize
) {
    public PadesSignatureProperties {
        enabled = enabled == null || enabled;
        signingSessionTimeout = signingSessionTimeout == null ? Duration.ofMinutes(10) : signingSessionTimeout;
        workerBatchSize = workerBatchSize == null ? 20 : workerBatchSize;
        if (signingSessionTimeout.isNegative() || signingSessionTimeout.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("finora.signature.pades.signing-session-timeout phải từ 1 phút");
        }
        if (workerBatchSize < 1 || workerBatchSize > 200) {
            throw new IllegalArgumentException("finora.signature.pades.worker-batch-size phải từ 1 đến 200");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
