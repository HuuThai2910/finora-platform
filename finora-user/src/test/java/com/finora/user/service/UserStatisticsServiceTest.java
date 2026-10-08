package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.user.dto.response.UserStatsSeriesResponse;
import com.finora.user.dto.response.UserStatsSeriesResponse.Point;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.repository.UserStatisticsRepository.EkycBucketRow;
import com.finora.user.repository.UserStatisticsRepository.RegistrationBucketRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatisticsServiceTest {

    /** 20:00 UTC ngày 7/10 đã là 3:00 sáng 8/10 ở Việt Nam: "hôm nay" phải là 8/10. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T20:00:00Z"), ZoneOffset.UTC);

    @Mock
    private UserStatisticsRepository repository;

    private UserStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new UserStatisticsService(repository, CLOCK);
    }

    @Test
    void fillsEveryWeekAndMergesEkycIntoRegistrationBuckets() {
        LocalDate week1 = LocalDate.of(2026, 9, 21);
        LocalDate week2 = LocalDate.of(2026, 9, 28);
        LocalDate week3 = LocalDate.of(2026, 10, 5);
        when(repository.countRegistrations(any())).thenReturn(List.of(
                new RegistrationBucketRow(week2, 9, 6, 3)));
        when(repository.countEkycCompletions(any())).thenReturn(List.of(
                new EkycBucketRow(week2, 5, 1),
                // eKYC xong ở tuần không có ai đăng ký mới vẫn phải hiện ra.
                new EkycBucketRow(week3, 2, 0)));

        UserStatsSeriesResponse response = service.series(
                LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 8), StatisticsBucket.WEEK);

        // from lùi về thứ Hai, to kéo tới Chủ nhật cuối tuần.
        assertThat(response.from()).isEqualTo(week1);
        assertThat(response.to()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(response.bucket()).isEqualTo(StatisticsBucket.WEEK);
        assertThat(response.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(response.points()).containsExactly(
                Point.empty(week1),
                new Point(week2, 9, 6, 3, 5, 1),
                new Point(week3, 0, 0, 0, 2, 0));
    }

    @Test
    void defaultsToLast30DaysInVietnamTimeAndQueriesBothMetricsWithSamePeriod() {
        when(repository.countRegistrations(any())).thenReturn(List.of());
        when(repository.countEkycCompletions(any())).thenReturn(List.of());

        UserStatsSeriesResponse response = service.series(null, null, null);

        assertThat(response.bucket()).isEqualTo(StatisticsBucket.DAY);
        assertThat(response.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(response.from()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(response.points()).hasSize(30).allMatch(p -> p.registered() == 0 && p.ekycVerified() == 0);

        ArgumentCaptor<StatisticsPeriod> captor = ArgumentCaptor.forClass(StatisticsPeriod.class);
        verify(repository).countRegistrations(captor.capture());
        verify(repository).countEkycCompletions(captor.getValue());
        assertThat(captor.getValue().startInclusive()).isEqualTo(Instant.parse("2026-09-08T17:00:00Z"));
        assertThat(captor.getValue().endExclusive()).isEqualTo(Instant.parse("2026-10-08T17:00:00Z"));
    }

    @Test
    void rejectsFromAfterToWithoutQuerying() {
        assertThatThrownBy(() -> service.series(
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 1), StatisticsBucket.DAY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(StatisticsPeriod.INVALID_RANGE_CODE);
        verifyNoInteractions(repository);
    }
}
