package com.finora.investment.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.common.exception.BusinessException;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.investment.dto.response.InvestmentStatisticsSeriesResponse;
import com.finora.investment.dto.response.InvestmentStatisticsSeriesResponse.Point;
import com.finora.investment.dto.response.InvestmentStatisticsSummaryResponse;
import com.finora.investment.repository.InvestmentStatisticsRepository;
import com.finora.investment.repository.InvestmentStatisticsRepository.AutoInvestBucketRow;
import com.finora.investment.repository.InvestmentStatisticsRepository.CommitmentBucketRow;
import com.finora.investment.repository.InvestmentStatisticsRepository.CountBucketRow;
import com.finora.investment.repository.InvestmentStatisticsRepository.ListingStatusRow;
import com.finora.investment.repository.InvestmentStatisticsRepository.NoteStatusRow;
import com.finora.investment.repository.InvestmentStatisticsRepository.TradeBucketRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestmentStatisticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T02:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private InvestmentStatisticsRepository repository;

    private InvestmentStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new InvestmentStatisticsService(repository, CLOCK);
    }

    @Test
    void summaryComputesFillRateAndZeroFillsStatuses() {
        when(repository.listingsByStatus()).thenReturn(List.of(
                new ListingStatusRow("OPEN", 12, new BigDecimal("500000000"), new BigDecimal("206000000")),
                new ListingStatusRow("FULLY_FUNDED", 30, new BigDecimal("900000000"), new BigDecimal("900000000"))));
        when(repository.notesByStatus()).thenReturn(List.of(
                new NoteStatusRow("ACTIVE", 900, new BigDecimal("812345678.5")),
                new NoteStatusRow("CLOSED", 40, BigDecimal.ZERO)));
        when(repository.countActiveAutoInvestConfigs()).thenReturn(4L);

        InvestmentStatisticsSummaryResponse summary = service.summary();

        assertThat(summary.asOf()).isEqualTo(NOW);
        assertThat(summary.listings().byStatus()).containsExactly(
                entry("DRAFT", 0L), entry("OPEN", 12L), entry("FULLY_FUNDED", 30L),
                entry("CLOSED", 0L), entry("CANCELLED", 0L));
        assertThat(summary.openFunding().listings()).isEqualTo(12);
        assertThat(summary.openFunding().targetAmount()).isEqualTo(new BigDecimal("500000000.00"));
        assertThat(summary.openFunding().committedAmount()).isEqualTo(new BigDecimal("206000000.00"));
        assertThat(summary.openFunding().fillRatePercent()).isEqualTo(new BigDecimal("41.20"));
        assertThat(summary.notes().byStatus()).containsExactly(
                entry("ACTIVE", 900L), entry("CLOSED", 40L), entry("DEFAULTED", 0L));
        assertThat(summary.notes().activeOutstandingPrincipal()).isEqualTo(new BigDecimal("812345678.50"));
        assertThat(summary.autoInvest().activeConfigs()).isEqualTo(4);
    }

    @Test
    void summaryWithoutOpenListingsHasNullFillRateAndZeroMoney() {
        when(repository.listingsByStatus()).thenReturn(List.of());
        when(repository.notesByStatus()).thenReturn(List.of());
        when(repository.countActiveAutoInvestConfigs()).thenReturn(0L);

        InvestmentStatisticsSummaryResponse summary = service.summary();

        assertThat(summary.openFunding().listings()).isZero();
        assertThat(summary.openFunding().targetAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(summary.openFunding().committedAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(summary.openFunding().fillRatePercent()).isNull();
        assertThat(summary.notes().activeOutstandingPrincipal()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void seriesMergesMetricsAndFillsEmptyBuckets() {
        LocalDate day1 = LocalDate.of(2026, 10, 1);
        LocalDate day2 = LocalDate.of(2026, 10, 2);
        LocalDate day3 = LocalDate.of(2026, 10, 3);
        when(repository.commitmentsByBucket(any())).thenReturn(List.of(
                new CommitmentBucketRow(day1, 2, new BigDecimal("3000000"))));
        when(repository.fullyFundedListingsByBucket(any())).thenReturn(List.of(new CountBucketRow(day3, 1)));
        when(repository.tradesByBucket(any())).thenReturn(List.of(
                // 985 × 3 + 990 × 1 = 3945 trên 4 Note → 986,25 → 986.
                // Riêng Note chưa nợ xấu (lần khớp 990 × 1 là nợ xấu bị loại): 985 × 3 / 3 → 985.
                new TradeBucketRow(day3, 2, new BigDecimal("3900000"), new BigDecimal("19500"), 3945, 4,
                        1, 2955, 3),
                // Cột chỉ có khớp Note nợ xấu: giá chung có, giá performing phải null chứ không phải 0.
                new TradeBucketRow(day2, 1, new BigDecimal("500000"), new BigDecimal("2500"), 500, 1,
                        0, 0, 0)));
        when(repository.autoInvestByBucket(any())).thenReturn(List.of(new AutoInvestBucketRow(day1, 1, 3)));

        InvestmentStatisticsSeriesResponse response = service.series(day1, day3, StatisticsBucket.DAY);

        assertThat(response.from()).isEqualTo(day1);
        assertThat(response.to()).isEqualTo(day3);
        assertThat(response.bucket()).isEqualTo(StatisticsBucket.DAY);
        assertThat(response.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(response.points()).containsExactly(
                new Point(day1, new BigDecimal("3000000.00"), 2, 0, 0,
                        new BigDecimal("0.00"), new BigDecimal("0.00"), null, 0, 0, 0, null, 1, 3),
                new Point(day2, new BigDecimal("0.00"), 0, 0, 1,
                        new BigDecimal("500000.00"), new BigDecimal("2500.00"), 500, 1, 0, 0, null, 0, 0),
                new Point(day3, new BigDecimal("0.00"), 0, 1, 2,
                        new BigDecimal("3900000.00"), new BigDecimal("19500.00"), 986, 4, 1, 3, 985, 0, 0));
    }

    @Test
    void averagePriceIsQuantityWeightedRoundedHalfUpAndNullWithoutVolume() {
        // Bình quân đơn giản của 900 (1 Note) và 1000 (99 Note) là 950; có trọng số là 999.
        assertThat(InvestmentStatisticsService.averagePricePermille(900 + 1000 * 99, 100)).isEqualTo(999);
        // 985 và 986 mỗi giá 1 Note → 985,5 → HALF_UP thành 986.
        assertThat(InvestmentStatisticsService.averagePricePermille(985 + 986, 2)).isEqualTo(986);
        assertThat(InvestmentStatisticsService.averagePricePermille(0, 0)).isNull();
    }

    @Test
    void fillRateRoundsHalfUpToTwoDecimals() {
        assertThat(InvestmentStatisticsService.fillRatePercent(new BigDecimal("1"), new BigDecimal("3")))
                .isEqualTo(new BigDecimal("33.33"));
        assertThat(InvestmentStatisticsService.fillRatePercent(new BigDecimal("2"), new BigDecimal("3")))
                .isEqualTo(new BigDecimal("66.67"));
        assertThat(InvestmentStatisticsService.fillRatePercent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }

    @Test
    void seriesRejectsInvalidRangeWithoutQuerying() {
        assertThatThrownBy(() -> service.series(
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 8), StatisticsBucket.DAY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(StatisticsPeriod.INVALID_RANGE_CODE);
        verifyNoInteractions(repository);
    }

    private static Map.Entry<String, Long> entry(String key, Long value) {
        return Map.entry(key, value);
    }
}
