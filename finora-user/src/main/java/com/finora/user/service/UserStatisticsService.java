package com.finora.user.service;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.user.dto.response.UserStatsSeriesResponse;
import com.finora.user.dto.response.UserStatsSeriesResponse.Point;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.repository.UserStatisticsRepository.EkycBucketRow;
import com.finora.user.repository.UserStatisticsRepository.RegistrationBucketRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Chuỗi thống kê người dùng theo thời gian cho trang quản trị (STATS-001).
 * <p>
 * Ảnh chụp hiện tại theo vai trò/eKYC vẫn ở {@code /admin/users/stats}; lớp này chỉ lo phần theo cột.
 */
@Service
@RequiredArgsConstructor
public class UserStatisticsService {

    private final UserStatisticsRepository statisticsRepository;
    private final Clock clock;

    /**
     * Người dùng mới và eKYC hoàn tất theo từng cột trong khoảng {@code from..to}.
     * <p>
     * Hai câu đọc chạy trong cùng transaction chỉ đọc; ở READ COMMITTED hai câu vẫn có thể thấy hai ảnh
     * dữ liệu lệch nhau vài mili giây, chấp nhận được với số liệu thống kê.
     *
     * @throws com.finora.common.exception.BusinessException 400 {@code STATISTICS_RANGE_INVALID} khi khoảng sai
     */
    @Transactional(readOnly = true)
    public UserStatsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket) {
        StatisticsPeriod period = StatisticsPeriod.of(from, to, bucket, clock);

        Map<LocalDate, Point> byBucket = new HashMap<>();
        for (RegistrationBucketRow row : statisticsRepository.countRegistrations(period)) {
            byBucket.put(row.bucketStart(), new Point(
                    row.bucketStart(), row.registered(), row.borrowers(), row.investors(), 0, 0));
        }
        // Cột eKYC cắt theo mốc hoàn tất, có thể rơi vào cột không có người đăng ký mới nên phải gộp.
        for (EkycBucketRow row : statisticsRepository.countEkycCompletions(period)) {
            Point current = byBucket.getOrDefault(row.bucketStart(), Point.empty(row.bucketStart()));
            byBucket.put(row.bucketStart(), new Point(
                    row.bucketStart(),
                    current.registered(),
                    current.registeredBorrowers(),
                    current.registeredInvestors(),
                    row.verified(),
                    row.failed()));
        }

        return new UserStatsSeriesResponse(
                period.from(),
                period.to(),
                period.bucket(),
                period.zone().getId(),
                period.dense(byBucket, Point::empty));
    }
}
