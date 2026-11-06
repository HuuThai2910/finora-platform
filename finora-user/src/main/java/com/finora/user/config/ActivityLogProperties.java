package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Nhật ký hoạt động của web quản trị: đọc access log của Gateway (dòng {@code finora.api}) từ Azure Log Analytics.
 * Bỏ trống {@code workspaceId} (máy dev) thì API trả 503 "chưa cấu hình".
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finora.activity-log")
public class ActivityLogProperties {

    /** Workspace ID (customerId) của Log Analytics workspace {@code finora-logs}. */
    private String workspaceId = "";

    /** Endpoint truy vấn của Log Analytics. */
    private String queryUrl = "https://api.loganalytics.io/v1/workspaces/{workspaceId}/query";

    /**
     * Nơi lấy token: Instance Metadata Service của VM Azure, cấp token cho managed identity của VM (đã có role
     * "Log Analytics Reader" trên workspace). Không cần lưu secret nào.
     */
    private String tokenUrl =
            "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://api.loganalytics.io";

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(20);

    /** Trang sâu nhất xem được (offset + size): Log Analytics trả dòng theo thứ tự rồi cắt ở service. */
    private int maxRows = 5000;
}
