package com.finora.user.service.impl;

import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.user.dto.response.UserStatsSeriesResponse.Point;
import com.finora.user.dto.response.UserStatsSeriesResponse;
import com.finora.user.repository.UserStatisticsRepository.EkycBucketRow;
import com.finora.user.repository.UserStatisticsRepository.RegistrationBucketRow;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.service.UserStatisticsService;
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
public class UserStatisticsServiceImpl implements UserStatisticsService {

    private final UserStatisticsRepository statisticsRepository;
    private final Clock clock;

    @Override
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
