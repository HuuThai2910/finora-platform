package com.finora.loan.service.statistics;

import com.finora.common.security.SecurityUtils;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanFundingStatus;
import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.CollectionStage;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.servicing.DebtGroup;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.ReconciliationIncidentStatus;
import com.finora.loan.dto.statistics.response.LoanStatisticsSeriesResponse;
import com.finora.loan.dto.statistics.response.LoanStatisticsSummaryResponse;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Số liệu thống kê danh mục cho vay cho quản trị viên (STATS-001).
 *
 * <p>Repository chỉ trả dòng tổng hợp; mọi quy tắc nghiệp vụ (nhóm nợ, nợ xấu, cột điểm, trải cột rỗng)
 * nằm ở đây để kiểm thử được mà không cần database.</p>
 *
 * <p>Transaction chỉ đọc gom các câu của một request vào một kết nối. Ở READ COMMITTED mỗi câu vẫn thấy
 * dữ liệu đã commit tại lúc nó chạy, nên giữa các câu có thể lệch một vài bản ghi vừa đổi trạng thái;
 * chấp nhận được cho số liệu thống kê, đổi lại không giữ snapshot dài trên Neon dùng chung.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLoanStatisticsService {

    /** Khoản vay không có hạng định giá (ví dụ dữ liệu trước khi có định giá theo rủi ro). */
    static final String UNGRADED = "UNGRADED";

    static final int HISTOGRAM_BUCKETS = 10;
    static final int HISTOGRAM_WIDTH = 10;

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** Hạng chữ cái theo thứ tự tăng dần, "UNGRADED" luôn cuối. */
    private static final Comparator<String> GRADE_ORDER = Comparator
            .comparing((String grade) -> UNGRADED.equals(grade))
            .thenComparing(Function.identity());

    private final LoanStatisticsRepository statistics;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LoanStatisticsSummaryResponse summary() {
        SecurityUtils.requireAdmin();
        return new LoanStatisticsSummaryResponse(
                clock.instant(),
                applications(statistics.countApplicationsByStatusAndFunding()),
                portfolio(statistics.countLoansByStatus(), statistics.sumOutstandingByProductAndDpd(),
                        statistics.sumOutstandingByCreditGrade(), statistics.countApplicationsByProduct()),
                collections(statistics.countCollectionCasesByStageAndStatus()),
                new LoanStatisticsSummaryResponse.StatusBreakdown(
                        enumCounts(LoanRescheduleStatus.class, statistics.countReschedulesByStatus())),
                new LoanStatisticsSummaryResponse.StatusBreakdown(
                        enumCounts(ReconciliationIncidentStatus.class,
                                statistics.countReconciliationIncidentsByStatus())),
                creditScores(statistics.countLatestSucceededScoresByGradeAndScore()));
    }

    /**
     * @throws com.finora.common.exception.BusinessException 400 STATISTICS_RANGE_INVALID khi khoảng ngày sai
     */
    @Transactional(readOnly = true)
    public LoanStatisticsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket) {
        SecurityUtils.requireAdmin();
        StatisticsPeriod period = StatisticsPeriod.of(from, to, bucket, clock);
        List<BucketProductCountRow> submitted = statistics.countSubmittedByBucketAndProduct(period);
        List<DecisionCountRow> decisions = statistics.countDecisionsByBucket(period);
        List<BucketProductDisbursementRow> disbursed = statistics.sumDisbursedByBucketAndProduct(period);
        FunnelRow funnel = statistics.countFunnel(period);
        return new LoanStatisticsSeriesResponse(
                period.from(),
                period.to(),
                period.bucket(),
                period.zone().getId(),
                points(period, submitted, decisions, disbursed),
                productPoints(submitted, disbursed),
                new LoanStatisticsSeriesResponse.Funnel(funnel.submitted(), funnel.scored(), funnel.approved(),
                        funnel.termsAccepted(), funnel.fundingRequested(), funnel.fullyFunded(),
                        funnel.disbursed()));
    }

    // ---------- Summary ----------

    private static LoanStatisticsSummaryResponse.Applications applications(List<ApplicationStatusRow> rows) {
        long total = rows.stream().mapToLong(ApplicationStatusRow::total).sum();
        Map<String, Long> byStatus = enumCounts(LoanApplicationStatus.class, rows.stream()
                .map(row -> new KeyCountRow(row.status(), row.total())).toList());
        Map<String, Long> byFundingStatus = enumCounts(LoanFundingStatus.class, rows.stream()
                .filter(row -> row.fundingStatus() != null)
                .map(row -> new KeyCountRow(row.fundingStatus(), row.total())).toList());
        return new LoanStatisticsSummaryResponse.Applications(total, byStatus, byFundingStatus);
    }

    static LoanStatisticsSummaryResponse.Portfolio portfolio(
            List<KeyCountRow> loanStatusRows,
            List<OutstandingByProductDpdRow> outstandingRows,
            List<OutstandingByGradeRow> gradeRows,
            List<ProductApplicationsRow> productRows) {
        Map<Integer, Totals> byGroup = new TreeMap<>();
        for (int group = DebtGroup.FIRST; group <= DebtGroup.LAST; group++) {
            byGroup.put(group, new Totals());
        }
        Map<Long, Totals> byProduct = new HashMap<>();
        Totals all = new Totals();
        long stale = 0;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (OutstandingByProductDpdRow row : outstandingRows) {
            int group = DebtGroup.fromDaysPastDue(row.daysPastDue());
            boolean npl = DebtGroup.isNonPerforming(group);
            all.add(row.loans(), row.principalOutstanding(), row.overdueAmount(), npl);
            byGroup.get(group).add(row.loans(), row.principalOutstanding(), row.overdueAmount(), npl);
            byProduct.computeIfAbsent(row.productId(), id -> new Totals())
                    .add(row.loans(), row.principalOutstanding(), row.overdueAmount(), npl);
            totalOutstanding = totalOutstanding.add(orZero(row.totalOutstanding()));
            stale += row.staleProjections();
        }
        List<LoanStatisticsSummaryResponse.DebtGroupBreakdown> debtGroups = byGroup.entrySet().stream()
                .map(entry -> new LoanStatisticsSummaryResponse.DebtGroupBreakdown(entry.getKey(),
                        entry.getValue().loans, money(entry.getValue().principal),
                        money(entry.getValue().overdue)))
                .toList();
        List<LoanStatisticsSummaryResponse.ProductBreakdown> products = productRows.stream()
                .map(product -> {
                    Totals totals = byProduct.getOrDefault(product.productId(), new Totals());
                    return new LoanStatisticsSummaryResponse.ProductBreakdown(product.productId(), product.code(),
                            product.name(), product.applications(), totals.loans, money(totals.principal),
                            money(totals.nplPrincipal), nplRatioPercent(totals.nplPrincipal, totals.principal));
                })
                .toList();
        return new LoanStatisticsSummaryResponse.Portfolio(
                enumCounts(FinoraLoanStatus.class, loanStatusRows),
                all.loans,
                money(all.principal),
                money(totalOutstanding),
                money(all.overdue),
                money(all.nplPrincipal),
                nplRatioPercent(all.nplPrincipal, all.principal),
                stale,
                debtGroups,
                creditGrades(gradeRows),
                products);
    }

    private static List<LoanStatisticsSummaryResponse.CreditGradeBreakdown> creditGrades(
            List<OutstandingByGradeRow> rows) {
        Map<String, Totals> byGrade = new HashMap<>();
        for (OutstandingByGradeRow row : rows) {
            byGrade.computeIfAbsent(gradeOrUngraded(row.grade()), grade -> new Totals())
                    .add(row.loans(), row.principalOutstanding(), BigDecimal.ZERO, false);
        }
        return byGrade.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(GRADE_ORDER))
                .map(entry -> new LoanStatisticsSummaryResponse.CreditGradeBreakdown(entry.getKey(),
                        entry.getValue().loans, money(entry.getValue().principal)))
                .toList();
    }

    private static LoanStatisticsSummaryResponse.Collections collections(List<CollectionCaseRow> rows) {
        List<CollectionCaseRow> open = rows.stream()
                .filter(row -> CollectionCaseStatus.OPEN.name().equals(row.status())).toList();
        return new LoanStatisticsSummaryResponse.Collections(
                open.stream().mapToLong(CollectionCaseRow::total).sum(),
                enumCounts(CollectionStage.class, open.stream()
                        .map(row -> new KeyCountRow(row.stage(), row.total())).toList()),
                enumCounts(CollectionCaseStatus.class, rows.stream()
                        .map(row -> new KeyCountRow(row.status(), row.total())).toList()));
    }

    static LoanStatisticsSummaryResponse.CreditScores creditScores(List<ScoreRow> rows) {
        long[] histogram = new long[HISTOGRAM_BUCKETS];
        Map<String, Long> byGrade = new TreeMap<>(GRADE_ORDER);
        long assessed = 0;
        for (ScoreRow row : rows) {
            histogram[histogramBucket(row.score())] += row.total();
            byGrade.merge(gradeOrUngraded(row.grade()), row.total(), Long::sum);
            assessed += row.total();
        }
        List<LoanStatisticsSummaryResponse.ScoreBucket> buckets = new ArrayList<>(HISTOGRAM_BUCKETS);
        for (int index = 0; index < HISTOGRAM_BUCKETS; index++) {
            buckets.add(new LoanStatisticsSummaryResponse.ScoreBucket(
                    index * HISTOGRAM_WIDTH, (index + 1) * HISTOGRAM_WIDTH, histogram[index]));
        }
        return new LoanStatisticsSummaryResponse.CreditScores(assessed, new LinkedHashMap<>(byGrade), buckets);
    }

    /**
     * Cột của điểm nguyên (đã làm tròn xuống ở SQL) trên thang 0..100: cột rộng 10, điểm 100 rơi vào cột
     * cuối [90, 100] thay vì tạo cột thứ 11; điểm ngoài thang (dữ liệu lỗi) bị kẹp vào cột biên.
     */
    static int histogramBucket(int score) {
        return Math.max(0, Math.min(HISTOGRAM_BUCKETS - 1, Math.floorDiv(score, HISTOGRAM_WIDTH)));
    }

    // ---------- Series ----------

    static List<LoanStatisticsSeriesResponse.Point> points(
            StatisticsPeriod period,
            List<BucketProductCountRow> submitted,
            List<DecisionCountRow> decisions,
            List<BucketProductDisbursementRow> disbursed) {
        Map<LocalDate, PointTotals> byBucket = new HashMap<>();
        for (BucketProductCountRow row : submitted) {
            PointTotals totals = byBucket.computeIfAbsent(row.bucketStart(), key -> new PointTotals());
            totals.submitted += row.total();
            totals.submittedAmount = totals.submittedAmount.add(orZero(row.requestedAmount()));
        }
        for (DecisionCountRow row : decisions) {
            PointTotals totals = byBucket.computeIfAbsent(row.bucketStart(), key -> new PointTotals());
            totals.approved += row.approved();
            totals.rejected += row.rejected();
        }
        for (BucketProductDisbursementRow row : disbursed) {
            PointTotals totals = byBucket.computeIfAbsent(row.bucketStart(), key -> new PointTotals());
            totals.loansDisbursed += row.loans();
            totals.disbursedAmount = totals.disbursedAmount.add(orZero(row.amount()));
        }
        Map<LocalDate, LoanStatisticsSeriesResponse.Point> points = new HashMap<>();
        byBucket.forEach((start, totals) -> points.put(start, new LoanStatisticsSeriesResponse.Point(start,
                totals.submitted, money(totals.submittedAmount), totals.approved, totals.rejected,
                totals.loansDisbursed, money(totals.disbursedAmount))));
        return period.dense(points,
                start -> new LoanStatisticsSeriesResponse.Point(start, 0, ZERO_MONEY, 0, 0, 0, ZERO_MONEY));
    }

    /** Thưa: chỉ cặp (cột, sản phẩm) có hồ sơ nộp hoặc có tiền giải ngân, sắp theo thời gian rồi productId. */
    static List<LoanStatisticsSeriesResponse.ProductPoint> productPoints(
            List<BucketProductCountRow> submitted, List<BucketProductDisbursementRow> disbursed) {
        Map<BucketProduct, ProductTotals> byKey = new TreeMap<>(Comparator
                .comparing(BucketProduct::bucketStart).thenComparing(BucketProduct::productId));
        for (BucketProductCountRow row : submitted) {
            ProductTotals totals = byKey.computeIfAbsent(
                    new BucketProduct(row.bucketStart(), row.productId()), key -> new ProductTotals());
            totals.submitted += row.total();
            totals.submittedAmount = totals.submittedAmount.add(orZero(row.requestedAmount()));
        }
        for (BucketProductDisbursementRow row : disbursed) {
            ProductTotals totals = byKey.computeIfAbsent(
                    new BucketProduct(row.bucketStart(), row.productId()), key -> new ProductTotals());
            totals.disbursedAmount = totals.disbursedAmount.add(orZero(row.amount()));
        }
        return byKey.entrySet().stream()
                .filter(entry -> entry.getValue().submitted > 0 || entry.getValue().disbursedAmount.signum() != 0)
                .map(entry -> new LoanStatisticsSeriesResponse.ProductPoint(entry.getKey().bucketStart(),
                        entry.getKey().productId(), entry.getValue().submitted,
                        money(entry.getValue().submittedAmount), money(entry.getValue().disbursedAmount)))
                .toList();
    }

    // ---------- Quy tắc chung ----------

    /**
     * Tỷ lệ nợ xấu theo phần trăm, 2 chữ số, HALF_UP; null khi dư nợ gốc bằng 0 để web hiện "—" thay vì 0%.
     */
    static BigDecimal nplRatioPercent(BigDecimal nplPrincipal, BigDecimal principal) {
        if (principal == null || principal.signum() == 0) {
            return null;
        }
        return orZero(nplPrincipal).multiply(ONE_HUNDRED).divide(principal, 2, RoundingMode.HALF_UP);
    }

    /**
     * Đếm theo enum: đủ mọi giá trị (kể cả 0) theo thứ tự khai báo; giá trị lạ trong DB (không còn trong enum)
     * vẫn được giữ ở cuối để tổng không bị hụt.
     */
    static <E extends Enum<E>> Map<String, Long> enumCounts(Class<E> type, List<KeyCountRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (E value : type.getEnumConstants()) {
            counts.put(value.name(), 0L);
        }
        for (KeyCountRow row : rows) {
            if (row.key() != null) {
                counts.merge(row.key(), row.total(), Long::sum);
            }
        }
        return counts;
    }

    private static String gradeOrUngraded(String grade) {
        return grade == null || grade.isBlank() ? UNGRADED : grade;
    }

    private static BigDecimal money(BigDecimal value) {
        return orZero(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class Totals {
        private long loans;
        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal overdue = BigDecimal.ZERO;
        private BigDecimal nplPrincipal = BigDecimal.ZERO;

        private void add(long count, BigDecimal principalOutstanding, BigDecimal overdueAmount, boolean npl) {
            loans += count;
            principal = principal.add(orZero(principalOutstanding));
            overdue = overdue.add(orZero(overdueAmount));
            if (npl) {
                nplPrincipal = nplPrincipal.add(orZero(principalOutstanding));
            }
        }
    }

    private static final class PointTotals {
        private long submitted;
        private BigDecimal submittedAmount = BigDecimal.ZERO;
        private long approved;
        private long rejected;
        private long loansDisbursed;
        private BigDecimal disbursedAmount = BigDecimal.ZERO;
    }

    private static final class ProductTotals {
        private long submitted;
        private BigDecimal submittedAmount = BigDecimal.ZERO;
        private BigDecimal disbursedAmount = BigDecimal.ZERO;
    }

    private record BucketProduct(LocalDate bucketStart, long productId) {}
}
