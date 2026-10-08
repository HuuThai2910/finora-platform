package com.finora.common.statistics;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsPeriodTest {

    // 23:30 UTC ngày 7/10 đã là 6:30 sáng 8/10 ở Việt Nam.
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToLastThirtyDaysInVietnamTime() {
        StatisticsPeriod period = StatisticsPeriod.of(null, null, null, clock);

        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(period.bucket()).isEqualTo(StatisticsBucket.DAY);
        assertThat(period.bucketStarts()).hasSize(30);
    }

    @Test
    void boundariesAreVietnamMidnight() {
        StatisticsPeriod period = StatisticsPeriod.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), StatisticsBucket.DAY, clock);

        assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
    }

    @Test
    void monthBucketsCoverWholeMonths() {
        StatisticsPeriod period = StatisticsPeriod.of(LocalDate.of(2026, 5, 15), LocalDate.of(2026, 10, 8), StatisticsBucket.MONTH, clock);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(period.bucketStarts()).containsExactly(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
    }

    @Test
    void weekBucketsStartOnMonday() {
        // 8/10/2026 là thứ Năm.
        StatisticsPeriod period = StatisticsPeriod.of(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), StatisticsBucket.WEEK, clock);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void denseFillsMissingBuckets() {
        StatisticsPeriod period = StatisticsPeriod.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), StatisticsBucket.DAY, clock);

        assertThat(period.dense(Map.of(LocalDate.of(2026, 10, 2), 5), start -> 0)).containsExactly(0, 5, 0);
    }

    @Test
    void rejectsReversedRange() {
        assertThatThrownBy(() -> StatisticsPeriod.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), StatisticsBucket.DAY, clock))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(StatisticsPeriod.INVALID_RANGE_CODE);
    }

    @Test
    void rejectsTooManyBuckets() {
        assertThatThrownBy(() -> StatisticsPeriod.of(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1), StatisticsBucket.DAY, clock))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(StatisticsPeriod.INVALID_RANGE_CODE);
    }
}
