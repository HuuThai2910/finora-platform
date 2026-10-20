package com.finora.common.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình gauge vận hành (W3E, D58). Các bộ đếm sự cố đọc database của chính service theo lịch trên một luồng riêng,
 * không chạy trên luồng request, luồng scrape metric hay scheduler của worker nghiệp vụ.
 *
 * @param enabled         tắt toàn bộ việc đăng ký gauge và lịch làm mới
 * @param refreshInterval khoảng cách giữa hai lần đếm; hàng đợi sự cố không cần độ trễ dưới một phút
 * @param initialDelay    chờ sau khi khởi động để Flyway và pool kết nối ổn định trước lần đếm đầu
 * @param countCap        trần của mỗi câu đếm: hàng đợi vượt trần chỉ báo "ít nhất countCap", để câu đếm không quét
 *                        không giới hạn đúng lúc sự cố dồn nhiều dòng
 * @param queryTimeout    thời gian tối đa của một câu đếm; quá hạn thì gauge báo NaN và tăng bộ đếm lỗi
 */
@ConfigurationProperties(prefix = "finora.operations.metrics")
public record OperationalMetricsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration refreshInterval,
        @DefaultValue("20s") Duration initialDelay,
        @DefaultValue("10000") int countCap,
        @DefaultValue("5s") Duration queryTimeout
) {

    public OperationalMetricsProperties {
        refreshInterval = requirePositive(refreshInterval, "refreshInterval");
        initialDelay = initialDelay == null || initialDelay.isNegative() ? Duration.ZERO : initialDelay;
        queryTimeout = requirePositive(queryTimeout, "queryTimeout");
        if (countCap <= 0) {
            throw new IllegalArgumentException("finora.operations.metrics.countCap phải lớn hơn 0");
        }
        if (queryTimeout.toSeconds() < 1) {
            // JDBC chỉ nhận timeout theo giây; dưới một giây sẽ thành 0 nghĩa là không giới hạn.
            throw new IllegalArgumentException("finora.operations.metrics.queryTimeout tối thiểu 1 giây");
        }
    }

    /** Giá trị mặc định như khi không cấu hình gì, dùng cho test và nơi khởi tạo thủ công. */
    public static OperationalMetricsProperties defaults() {
        return new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ofSeconds(20), 10_000,
                Duration.ofSeconds(5));
    }

    /** Số giây truyền cho {@code Statement.setQueryTimeout}. */
    public int queryTimeoutSeconds() {
        return Math.toIntExact(queryTimeout.toSeconds());
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("finora.operations.metrics." + field + " phải lớn hơn 0");
        }
        return value;
    }
}
