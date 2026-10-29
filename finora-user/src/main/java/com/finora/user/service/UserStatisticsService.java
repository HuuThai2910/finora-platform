package com.finora.user.service;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.user.dto.response.UserStatsSeriesResponse;

import java.time.LocalDate;

/**
 * Chuỗi thống kê người dùng theo thời gian cho trang quản trị (STATS-001).
 */
public interface UserStatisticsService {

    /**
     * Người dùng mới và eKYC hoàn tất theo từng cột trong khoảng {@code from..to}.
     * <p>
     * Hai câu đọc chạy trong cùng transaction chỉ đọc; ở READ COMMITTED hai câu vẫn có thể thấy hai ảnh
     * dữ liệu lệch nhau vài mili giây, chấp nhận được với số liệu thống kê.
     *
     * @throws com.finora.common.exception.BusinessException 400 {@code STATISTICS_RANGE_INVALID} khi khoảng sai
     */
    UserStatsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket);
}
