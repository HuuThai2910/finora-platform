package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cấu hình lối chuyển nhân viên duyệt tay khi finora-ai gặp sự cố ({@code finora.ekyc.manual-review.*}).
 * <p>
 * Người dùng chỉ được gửi hồ sơ cho nhân viên khi AI lỗi {@link #aiFailureThreshold} lần <b>liên tiếp</b>
 * (gộp bước đọc thẻ và bước liveness). Một lần lỗi lẻ thường là AI khởi động lại hoặc mạng chập chờn — thử lại
 * là được, không đáng tốn công nhân viên.
 */
@ConfigurationProperties(prefix = "finora.ekyc.manual-review")
@Getter
@Setter
public class EkycManualReviewProperties {

    /** Số lần AI lỗi liên tiếp để mở lối duyệt tay; nhỏ hơn 1 được coi là 1. */
    private int aiFailureThreshold = 3;

    /** Bộ đếm hết hạn sau khoảng này kể từ lần lỗi gần nhất (làm mới mỗi lần lỗi). */
    private Duration aiFailureWindow = Duration.ofHours(1);
}
