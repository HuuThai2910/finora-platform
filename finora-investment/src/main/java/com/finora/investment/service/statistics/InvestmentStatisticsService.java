package com.finora.investment.service.statistics;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.NoteStatus;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Số liệu sàn gọi vốn, Notes và Auto-Invest cho trang thống kê quản trị (STATS-001). Chỉ đọc.
 *
 * <p>Các câu trong một request chạy chung một transaction chỉ đọc; ở READ COMMITTED các câu vẫn có thể
 * thấy dữ liệu lệch nhau vài mili giây (ví dụ một lệnh vừa khớp giữa hai câu). Chấp nhận được với số liệu
 * thống kê, đổi lại không phải khoá bảng nào.</p>
 */
@Service
@RequiredArgsConstructor
public class InvestmentStatisticsService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvestmentStatisticsRepository statisticsRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InvestmentStatisticsSummaryResponse summary() {
        List<ListingStatusRow> listingRows = statisticsRepository.listingsByStatus();
        List<NoteStatusRow> noteRows = statisticsRepository.notesByStatus();
        long activeConfigs = statisticsRepository.countActiveAutoInvestConfigs();

        Map<String, Long> listingsByStatus = zeroFilled(ListingStatus.values());
        listingRows.forEach(row -> listingsByStatus.put(row.status(), row.listings()));

        ListingStatusRow open = listingRows.stream()
                .filter(row -> ListingStatus.OPEN.name().equals(row.status()))
                .findFirst()
                .orElse(new ListingStatusRow(ListingStatus.OPEN.name(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal target = money(open.targetAmount());
        BigDecimal committed = money(open.committedAmount());

        Map<String, Long> notesByStatus = zeroFilled(NoteStatus.values());
        noteRows.forEach(row -> notesByStatus.put(row.status(), row.notes()));
        BigDecimal activeOutstanding = money(noteRows.stream()
                .filter(row -> NoteStatus.ACTIVE.name().equals(row.status()))
                .map(NoteStatusRow::outstandingPrincipal)
                .findFirst()
                .orElse(null));

        return new InvestmentStatisticsSummaryResponse(
                clock.instant(),
                new InvestmentStatisticsSummaryResponse.Listings(listingsByStatus),
                new InvestmentStatisticsSummaryResponse.OpenFunding(
                        open.listings(), target, committed, fillRatePercent(committed, target)),
                new InvestmentStatisticsSummaryResponse.Notes(notesByStatus, activeOutstanding),
                new InvestmentStatisticsSummaryResponse.AutoInvest(activeConfigs));
    }

    /**
     * Chuỗi theo cột trong khoảng {@code from..to}: bốn câu {@code GROUP BY} (vốn góp, listing đủ vốn,
     * khớp lệnh, Auto-Invest) rồi ghép theo ngày đầu cột và trải đủ mọi cột.
     *
     * @throws com.finora.common.exception.BusinessException 400 {@code STATISTICS_RANGE_INVALID} khi khoảng sai
     */
    @Transactional(readOnly = true)
    public InvestmentStatisticsSeriesResponse series(LocalDate from, LocalDate to, StatisticsBucket bucket) {
        StatisticsPeriod period = StatisticsPeriod.of(from, to, bucket, clock);

        Map<LocalDate, CommitmentBucketRow> commitments = byBucket(
                statisticsRepository.commitmentsByBucket(period), CommitmentBucketRow::bucketStart);
        Map<LocalDate, CountBucketRow> fullyFunded = byBucket(
                statisticsRepository.fullyFundedListingsByBucket(period), CountBucketRow::bucketStart);
        Map<LocalDate, TradeBucketRow> trades = byBucket(
                statisticsRepository.tradesByBucket(period), TradeBucketRow::bucketStart);
        Map<LocalDate, AutoInvestBucketRow> autoInvest = byBucket(
                statisticsRepository.autoInvestByBucket(period), AutoInvestBucketRow::bucketStart);

        // Ghép bốn bản đồ theo từng cột của khoảng: cột không có dòng nào thành 0, không cần dense riêng.
        Map<LocalDate, Point> points = new LinkedHashMap<>();
        for (LocalDate start : period.bucketStarts()) {
            CommitmentBucketRow commitment = commitments.get(start);
            CountBucketRow funded = fullyFunded.get(start);
            TradeBucketRow trade = trades.get(start);
            AutoInvestBucketRow auto = autoInvest.get(start);
            if (commitment == null && funded == null && trade == null && auto == null) {
                continue;
            }
            points.put(start, new Point(
                    start,
                    money(commitment == null ? null : commitment.committedAmount()),
                    commitment == null ? 0 : commitment.commitments(),
                    funded == null ? 0 : funded.count(),
                    trade == null ? 0 : trade.trades(),
                    money(trade == null ? null : trade.tradedAmount()),
                    money(trade == null ? null : trade.platformFee()),
                    trade == null ? null : averagePricePermille(trade.priceQuantity(), trade.quantity()),
                    trade == null ? 0 : trade.quantity(),
                    trade == null ? 0 : trade.performingTrades(),
                    trade == null ? 0 : trade.performingQuantity(),
                    // Cột chỉ có khớp Note nợ xấu cho null (không phải 0) để web bỏ cột đó khỏi trung bình.
                    trade == null ? null
                            : averagePricePermille(trade.performingPriceQuantity(), trade.performingQuantity()),
                    auto == null ? 0 : auto.placed(),
                    auto == null ? 0 : auto.skipped()));
        }

        return new InvestmentStatisticsSeriesResponse(
                period.from(),
                period.to(),
                period.bucket(),
                period.zone().getId(),
                period.dense(points, InvestmentStatisticsService::emptyPoint));
    }

    /**
     * Giá bình quân theo khối lượng: Σ(giá × số Note) / Σ số Note, làm tròn HALF_UP về số nguyên phần nghìn.
     * Bình quân đơn giản sẽ để một lần khớp 1 Note giá thấp kéo giá cả ngày xuống ngang lần khớp 100 Note.
     *
     * @return {@code null} khi không có Note nào được khớp, để web không vẽ giá 0 như thể bán cho không
     */
    static Integer averagePricePermille(long priceQuantity, long quantity) {
        if (quantity <= 0) {
            return null;
        }
        return BigDecimal.valueOf(priceQuantity)
                .divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** Tỷ lệ gom vốn 2 chữ số; {@code null} khi mục tiêu bằng 0 (không có listing mở) thay vì chia cho 0. */
    static BigDecimal fillRatePercent(BigDecimal committed, BigDecimal target) {
        if (target == null || target.signum() == 0) {
            return null;
        }
        return committed.multiply(HUNDRED).divide(target, 2, RoundingMode.HALF_UP);
    }

    private static Point emptyPoint(LocalDate bucketStart) {
        return new Point(bucketStart, money(null), 0, 0, 0, money(null), money(null), null, 0, 0, 0, null, 0, 0);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Đủ mọi trạng thái của enum theo thứ tự khai báo, để web không phải đoán khoá nào vắng nghĩa là 0. */
    private static Map<String, Long> zeroFilled(Enum<?>[] values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            counts.put(value.name(), 0L);
        }
        return counts;
    }

    private static <T> Map<LocalDate, T> byBucket(List<T> rows, Function<T, LocalDate> key) {
        return rows.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
