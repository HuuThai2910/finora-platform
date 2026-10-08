package com.finora.user.dto.response;

import com.finora.common.statistics.StatisticsBucket;

import java.time.LocalDate;
import java.util.List;

/**
 * Chuỗi người dùng mới và eKYC theo cột thời gian cho trang thống kê quản trị.
 * <p>
 * {@code from}/{@code to} là khoảng đã nới cho tròn cột (xem {@code StatisticsPeriod}), nên web vẽ trục
 * theo hai giá trị này chứ không theo ngày người dùng chọn. {@code points} luôn đủ mọi cột, kể cả cột 0.
 * Chỉ có số đếm, không có PII.
 *
 * @param timezone múi giờ dùng để cắt cột, luôn {@code Asia/Ho_Chi_Minh}
 */
public record UserStatsSeriesResponse(
        LocalDate from,
        LocalDate to,
        StatisticsBucket bucket,
        String timezone,
        List<Point> points
) {

    /**
     * Số liệu của một cột.
     *
     * @param bucketStart         ngày đầu cột theo giờ Việt Nam
     * @param registered          số hồ sơ tạo trong cột, mọi vai trò (kể cả ADMIN)
     * @param registeredBorrowers phần trong {@code registered} có vai trò hiện tại BORROWER
     * @param registeredInvestors phần trong {@code registered} có vai trò hiện tại INVESTOR
     * @param ekycVerified        số hồ sơ hoàn tất eKYC trong cột và hiện đang VERIFIED
     * @param ekycFailed          số hồ sơ có mốc hoàn tất eKYC trong cột và hiện đang FAILED
     */
    public record Point(
            LocalDate bucketStart,
            long registered,
            long registeredBorrowers,
            long registeredInvestors,
            long ekycVerified,
            long ekycFailed
    ) {

        public static Point empty(LocalDate bucketStart) {
            return new Point(bucketStart, 0, 0, 0, 0, 0);
        }
    }
}
