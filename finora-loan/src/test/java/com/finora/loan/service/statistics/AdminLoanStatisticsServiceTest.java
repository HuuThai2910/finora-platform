package com.finora.loan.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.common.exception.BusinessException;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.loan.dto.statistics.response.LoanStatisticsSeriesResponse;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse.DebtGroupBreakdown;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse.ProductBreakdown;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse.ScoreBucket;
import com.finora.loan.repository.statistics.LoanStatisticsRepository;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.ApplicationStatusRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.BucketProductCountRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.BucketProductDisbursementRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.CollectionCaseRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.DecisionCountRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.FunnelRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.KeyCountRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.OutstandingByGradeRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.OutstandingByProductDpdRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.ProductApplicationsRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.ScoreRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AdminLoanStatisticsServiceTest {

    /** 08:00 ngày 8/10 theo giờ Việt Nam; cố định để mặc định "30 ngày gần nhất" không phụ thuộc ngày chạy. */
    private static final Instant NOW = Instant.parse("2026-10-08T01:00:00Z");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate D4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate D5 = LocalDate.of(2026, 10, 5);

    @Mock
    private LoanStatisticsRepository repository;

    private AdminLoanStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminLoanStatisticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        authenticateAs("ROLE_ADMIN");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void summaryGroupsDebtByDpdComputesNplAndKeepsEveryDebtGroupAndProduct() {
        stubEmptySummary();
        when(repository.countLoansByStatus()).thenReturn(List.of(
                new KeyCountRow("ACTIVE", 3), new KeyCountRow("DEFAULTED", 1), new KeyCountRow("SETTLED", 2)));
        when(repository.sumOutstandingByProductAndDpd()).thenReturn(List.of(
                outstanding(1L, 0, 2, "200.00", "220.00", "0.00", 0),
                outstanding(1L, 95, 1, "100.00", "130.00", "40.00", 0),
                outstanding(2L, 10, 1, "100.00", "105.00", "5.00", 1),
                outstanding(2L, 400, 1, "200.00", "260.00", "200.00", 0)));
        when(repository.countApplicationsByProduct()).thenReturn(List.of(
                new ProductApplicationsRow(1L, "VAY_NHANH", "Vay nhanh", 7),
                new ProductApplicationsRow(2L, "VAY_KINH_DOANH", "Vay kinh doanh", 3),
                new ProductApplicationsRow(3L, "VAY_MOI", "Vay mới", 0)));

        LoanStatisticsSummaryResponse.Portfolio portfolio = service.summary().portfolio();

        assertThat(portfolio.loansByStatus()).containsExactly(
                entry("ACTIVE", 3L), entry("RESTRUCTURING", 0L), entry("SETTLED", 2L),
                entry("DEFAULTED", 1L), entry("WRITTEN_OFF", 0L));
        assertThat(portfolio.outstandingLoans()).isEqualTo(5);
        assertThat(portfolio.principalOutstanding()).isEqualByComparingTo("600.00");
        assertThat(portfolio.principalOutstanding().scale()).isEqualTo(2);
        assertThat(portfolio.totalOutstanding()).isEqualByComparingTo("715.00");
        assertThat(portfolio.overdueAmount()).isEqualByComparingTo("245.00");
        assertThat(portfolio.nplPrincipalOutstanding()).isEqualByComparingTo("300.00");
        assertThat(portfolio.nplRatioPercent()).isEqualByComparingTo("50.00");
        assertThat(portfolio.staleProjections()).isEqualTo(1);
        assertThat(portfolio.byDebtGroup()).extracting(DebtGroupBreakdown::debtGroup).containsExactly(1, 2, 3, 4, 5);
        assertThat(portfolio.byDebtGroup()).extracting(DebtGroupBreakdown::loans).containsExactly(2L, 1L, 1L, 0L, 1L);
        assertThat(portfolio.byDebtGroup().get(3).principalOutstanding()).isEqualByComparingTo("0.00");
        assertThat(portfolio.byDebtGroup().get(4).overdueAmount()).isEqualByComparingTo("200.00");

        ProductBreakdown fast = portfolio.byProduct().get(0);
        assertThat(fast.applications()).isEqualTo(7);
        assertThat(fast.outstandingLoans()).isEqualTo(3);
        assertThat(fast.nplPrincipalOutstanding()).isEqualByComparingTo("100.00");
        // 100 / 300 = 33.333...% → 33.33
        assertThat(fast.nplRatioPercent()).isEqualByComparingTo("33.33");
        // 200 / 300 = 66.666...% → 66.67 (HALF_UP)
        assertThat(portfolio.byProduct().get(1).nplRatioPercent()).isEqualByComparingTo("66.67");
        ProductBreakdown unused = portfolio.byProduct().get(2);
        assertThat(unused.outstandingLoans()).isZero();
        assertThat(unused.principalOutstanding()).isEqualByComparingTo("0.00");
        assertThat(unused.nplRatioPercent()).isNull();
    }

    @Test
    void nplRatioIsNullWhenNothingOutstanding() {
        stubEmptySummary();

        LoanStatisticsSummaryResponse.Portfolio portfolio = service.summary().portfolio();

        assertThat(portfolio.outstandingLoans()).isZero();
        assertThat(portfolio.principalOutstanding()).isEqualByComparingTo("0.00");
        assertThat(portfolio.nplRatioPercent()).isNull();
        assertThat(portfolio.byDebtGroup()).hasSize(5)
                .allSatisfy(group -> assertThat(group.loans()).isZero());
    }

    @ParameterizedTest
    @CsvSource({
            "1, 800, 0.13",       // 0.125 → HALF_UP lên 0.13 (HALF_EVEN sẽ ra 0.12)
            "1, 3, 33.33",
            "2, 3, 66.67",
            "0, 500, 0.00",
            "500, 500, 100.00"})
    void nplRatioRoundsHalfUpToTwoDecimals(String npl, String principal, String expected) {
        BigDecimal ratio = AdminLoanStatisticsService.nplRatioPercent(new BigDecimal(npl), new BigDecimal(principal));

        assertThat(ratio).isEqualByComparingTo(expected);
        assertThat(ratio.scale()).isEqualTo(2);
    }

    @Test
    void creditGradesPutUngradedLastAndMergeMissingGrade() {
        stubEmptySummary();
        when(repository.sumOutstandingByCreditGrade()).thenReturn(List.of(
                new OutstandingByGradeRow(null, 2, new BigDecimal("50.00")),
                new OutstandingByGradeRow("C", 1, new BigDecimal("30.00")),
                new OutstandingByGradeRow("A", 4, new BigDecimal("80.00"))));

        var grades = service.summary().portfolio().byCreditGrade();

        assertThat(grades).extracting(LoanStatisticsSummaryResponse.CreditGradeBreakdown::grade)
                .containsExactly("A", "C", "UNGRADED");
        assertThat(grades.get(2).loans()).isEqualTo(2);
    }

    @Test
    void histogramAlwaysHasTenBucketsAndScoreOneHundredFallsInTheLast() {
        LoanStatisticsSummaryResponse.CreditScores scores = AdminLoanStatisticsService.creditScores(List.of(
                new ScoreRow("D", 0, 1),
                new ScoreRow("D", 9, 2),
                new ScoreRow("C", 10, 3),
                new ScoreRow("A", 99, 4),
                new ScoreRow("A", 100, 5),
                new ScoreRow(null, 55, 1)));

        assertThat(scores.assessed()).isEqualTo(16);
        assertThat(scores.histogram()).hasSize(10);
        assertThat(scores.histogram()).extracting(ScoreBucket::from)
                .containsExactly(0, 10, 20, 30, 40, 50, 60, 70, 80, 90);
        assertThat(scores.histogram()).extracting(ScoreBucket::count)
                .containsExactly(3L, 3L, 0L, 0L, 0L, 1L, 0L, 0L, 0L, 9L);
        assertThat(scores.histogram().get(9).to()).isEqualTo(100);
        assertThat(scores.byGrade()).containsExactly(
                entry("A", 9L), entry("C", 3L), entry("D", 3L), entry("UNGRADED", 1L));
    }

    @Test
    void histogramIsEmptyButCompleteWithoutAssessments() {
        LoanStatisticsSummaryResponse.CreditScores scores = AdminLoanStatisticsService.creditScores(List.of());

        assertThat(scores.assessed()).isZero();
        assertThat(scores.histogram()).hasSize(10).allSatisfy(bucket -> assertThat(bucket.count()).isZero());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "9,0", "10,1", "89,8", "90,9", "100,9", "-1,0", "105,9"})
    void histogramBucketKeepsHundredInLastBucketAndClampsOutOfScale(int score, int expected) {
        assertThat(AdminLoanStatisticsService.histogramBucket(score)).isEqualTo(expected);
    }

    @Test
    void summaryFillsEveryEnumValueAndCountsOnlyOpenCollectionCasesByStage() {
        stubEmptySummary();
        when(repository.countApplicationsByStatusAndFunding()).thenReturn(List.of(
                new ApplicationStatusRow("APPROVED", "FULLY_FUNDED", 5),
                new ApplicationStatusRow("APPROVED", "REQUESTED", 2),
                new ApplicationStatusRow("APPROVED", null, 1),
                new ApplicationStatusRow("REJECTED", null, 4)));
        when(repository.countCollectionCasesByStageAndStatus()).thenReturn(List.of(
                new CollectionCaseRow("EARLY_REMINDER", "OPEN", 3),
                new CollectionCaseRow("NPL", "OPEN", 1),
                new CollectionCaseRow("EARLY_REMINDER", "CURED", 6)));
        when(repository.countReschedulesByStatus()).thenReturn(List.of(new KeyCountRow("PENDING_REVIEW", 2)));

        LoanStatisticsSummaryResponse summary = service.summary();

        assertThat(summary.asOf()).isEqualTo(NOW);
        assertThat(summary.applications().total()).isEqualTo(12);
        assertThat(summary.applications().byStatus()).containsEntry("APPROVED", 8L)
                .containsEntry("REJECTED", 4L).containsEntry("PENDING_REVIEW", 0L).hasSize(8);
        assertThat(summary.applications().byFundingStatus())
                .containsExactly(entry("REQUESTED", 2L), entry("FULLY_FUNDED", 5L));
        assertThat(summary.collections().openCases()).isEqualTo(4);
        assertThat(summary.collections().openByStage()).containsExactly(
                entry("EARLY_REMINDER", 3L), entry("ATTENTION", 0L), entry("NPL", 1L),
                entry("INTENSIVE", 0L), entry("LOSS", 0L));
        assertThat(summary.collections().byStatus())
                .containsEntry("OPEN", 4L).containsEntry("CURED", 6L).containsEntry("SETTLED", 0L);
        assertThat(summary.reschedules().byStatus()).containsEntry("PENDING_REVIEW", 2L)
                .containsEntry("COMPLETED", 0L);
        assertThat(summary.reconciliationIncidents().byStatus())
                .containsExactly(entry("OPEN", 0L), entry("RESOLVED", 0L));
    }

    @Test
    void seriesFillsEmptyBucketsAndKeepsProductPointsSparse() {
        when(repository.countSubmittedByBucketAndProduct(any())).thenReturn(List.of(
                new BucketProductCountRow(D2, 1L, 3, new BigDecimal("150000000")),
                new BucketProductCountRow(D2, 2L, 1, new BigDecimal("20000000.5")),
                new BucketProductCountRow(D4, 1L, 2, new BigDecimal("60000000"))));
        when(repository.countDecisionsByBucket(any())).thenReturn(List.of(new DecisionCountRow(D4, 2, 1)));
        when(repository.sumDisbursedByBucketAndProduct(any())).thenReturn(List.of(
                new BucketProductDisbursementRow(D4, 2L, 1, new BigDecimal("85000000"))));
        when(repository.countFunnel(any())).thenReturn(new FunnelRow(6, 5, 3, 2, 2, 1, 1));

        LoanStatisticsSeriesResponse series = service.series(D1, D5, StatisticsBucket.DAY);

        assertThat(series.from()).isEqualTo(D1);
        assertThat(series.to()).isEqualTo(D5);
        assertThat(series.bucket()).isEqualTo(StatisticsBucket.DAY);
        assertThat(series.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(series.points()).extracting(LoanStatisticsSeriesResponse.Point::bucketStart)
                .containsExactly(D1, D2, LocalDate.of(2026, 10, 3), D4, D5);
        assertThat(series.points()).extracting(LoanStatisticsSeriesResponse.Point::applicationsSubmitted)
                .containsExactly(0L, 4L, 0L, 2L, 0L);
        // Giá trị hồ sơ nộp cộng dồn mọi sản phẩm của cột, scale 2; cột không có hồ sơ là 0.00 chứ không null.
        assertThat(series.points()).extracting(LoanStatisticsSeriesResponse.Point::applicationsSubmittedAmount)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("170000000.50"), new BigDecimal("0.00"),
                        new BigDecimal("60000000.00"), new BigDecimal("0.00"));
        LoanStatisticsSeriesResponse.Point d4 = series.points().get(3);
        assertThat(d4.applicationsApproved()).isEqualTo(2);
        assertThat(d4.applicationsRejected()).isEqualTo(1);
        assertThat(d4.loansDisbursed()).isEqualTo(1);
        assertThat(d4.disbursedAmount()).isEqualByComparingTo("85000000.00");
        assertThat(d4.disbursedAmount().scale()).isEqualTo(2);
        assertThat(series.points().get(0).disbursedAmount()).isEqualByComparingTo("0.00");

        assertThat(series.productPoints()).containsExactly(
                new LoanStatisticsSeriesResponse.ProductPoint(D2, 1L, 3, new BigDecimal("150000000.00"),
                        new BigDecimal("0.00")),
                new LoanStatisticsSeriesResponse.ProductPoint(D2, 2L, 1, new BigDecimal("20000000.50"),
                        new BigDecimal("0.00")),
                new LoanStatisticsSeriesResponse.ProductPoint(D4, 1L, 2, new BigDecimal("60000000.00"),
                        new BigDecimal("0.00")),
                new LoanStatisticsSeriesResponse.ProductPoint(D4, 2L, 0, new BigDecimal("0.00"),
                        new BigDecimal("85000000.00")));
        assertThat(series.funnel()).isEqualTo(new LoanStatisticsSeriesResponse.Funnel(6, 5, 3, 2, 2, 1, 1));
    }

    @Test
    void seriesAlignsWeekBucketsAndPassesTheAlignedPeriodToTheRepository() {
        stubEmptySeries();

        // 1/10/2026 là thứ Năm → cột tuần bắt đầu thứ Hai 28/9; 8/10 là thứ Năm → cột cuối kết thúc Chủ nhật 11/10.
        LoanStatisticsSeriesResponse series = service.series(D1, LocalDate.of(2026, 10, 8), StatisticsBucket.WEEK);

        assertThat(series.from()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(series.to()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(series.points()).extracting(LoanStatisticsSeriesResponse.Point::bucketStart)
                .containsExactly(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5));
        ArgumentCaptor<StatisticsPeriod> period = ArgumentCaptor.forClass(StatisticsPeriod.class);
        verify(repository).countSubmittedByBucketAndProduct(period.capture());
        // Đầu ngày 28/9 giờ Việt Nam = 17:00 UTC ngày 27/9.
        assertThat(period.getValue().startInclusive()).isEqualTo(Instant.parse("2026-09-27T17:00:00Z"));
        assertThat(period.getValue().endExclusive()).isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
    }

    @Test
    void seriesDefaultsToLastThirtyDaysInVietnamTime() {
        stubEmptySeries();

        LoanStatisticsSeriesResponse series = service.series(null, null, null);

        assertThat(series.bucket()).isEqualTo(StatisticsBucket.DAY);
        assertThat(series.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(series.from()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(series.points()).hasSize(30);
    }

    @Test
    void seriesRejectsReversedRangeBeforeQuerying() {
        assertThatThrownBy(() -> service.series(D5, D1, StatisticsBucket.DAY))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("STATISTICS_RANGE_INVALID");
                });
        verifyNoInteractions(repository);
    }

    @Test
    void seriesRejectsMoreThanMaxBuckets() {
        assertThatThrownBy(() -> service.series(LocalDate.of(2025, 1, 1), D5, StatisticsBucket.DAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("STATISTICS_RANGE_INVALID"));
        verify(repository, never()).countFunnel(any());
    }

    @Test
    void nonAdminIsForbiddenForBothEndpoints() {
        authenticateAs("ROLE_BORROWER");

        assertThatThrownBy(() -> service.summary())
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.series(D1, D5, StatisticsBucket.DAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(repository);
    }

    private void stubEmptySummary() {
        when(repository.countApplicationsByStatusAndFunding()).thenReturn(List.of());
        when(repository.countLoansByStatus()).thenReturn(List.of());
        when(repository.sumOutstandingByProductAndDpd()).thenReturn(List.of());
        when(repository.sumOutstandingByCreditGrade()).thenReturn(List.of());
        when(repository.countApplicationsByProduct()).thenReturn(List.of());
        when(repository.countCollectionCasesByStageAndStatus()).thenReturn(List.of());
        when(repository.countReschedulesByStatus()).thenReturn(List.of());
        when(repository.countReconciliationIncidentsByStatus()).thenReturn(List.of());
        when(repository.countLatestSucceededScoresByGradeAndScore()).thenReturn(List.of());
    }

    private void stubEmptySeries() {
        when(repository.countSubmittedByBucketAndProduct(any())).thenReturn(List.of());
        when(repository.countDecisionsByBucket(any())).thenReturn(List.of());
        when(repository.sumDisbursedByBucketAndProduct(any())).thenReturn(List.of());
        when(repository.countFunnel(any())).thenReturn(new FunnelRow(0, 0, 0, 0, 0, 0, 0));
    }

    private static OutstandingByProductDpdRow outstanding(long productId, int dpd, long loans, String principal,
            String total, String overdue, long stale) {
        return new OutstandingByProductDpdRow(productId, dpd, loans, new BigDecimal(principal),
                new BigDecimal(total), new BigDecimal(overdue), stale);
    }

    private static java.util.Map.Entry<String, Long> entry(String key, long value) {
        return java.util.Map.entry(key, value);
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, role));
    }
}
