package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bật/tắt bước kiểm tra khuôn mặt (liveness) của eKYC ({@code finora.ekyc.liveness.*}).
 * <p>
 * Hải chốt 23/11: eKYC chỉ cần đọc CCCD. Tắt thì đọc thẻ xong đi thẳng sang soát và xác nhận ({@code nextStep=CONFIRM}),
 * giấy tờ {@code AUTO_OK} được xác minh tự động, còn lại vào duyệt tay; lối "gửi nhân viên duyệt" khi AI lỗi gửi luôn
 * hai ảnh thẻ, không quay video. Bật lại thì luồng liveness cũ chạy nguyên vẹn.
 */
@ConfigurationProperties(prefix = "finora.ekyc.liveness")
@Getter
@Setter
public class EkycLivenessProperties {

    private boolean enabled = false;
}
