package com.finora.investment.repository;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import com.finora.investment.domain.orderbook.SettlementStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu tổng hợp cho trang thống kê quản trị của Investment (STATS-001).
 *
 * <p>SQL thuần vì cần {@code date_trunc ... AT TIME ZONE} của PostgreSQL và chỉ đọc số tổng, không nạp
 * entity. Mỗi chỉ số đúng một câu {@code GROUP BY}, nên số câu cố định dù khoảng có 30 hay 366 cột.</p>
 *
 * <p>Cột cắt theo giờ Việt Nam (DB lưu UTC). Điều kiện lọc đặt trên cột thời gian gốc chưa bọc hàm để
 * dùng được index nếu có (ví dụ {@code idx_order_book_trades_executed_at}). Giá trị trạng thái truyền
 * dạng tham số lấy từ enum trong code, không viết cứng chuỗi trong câu SQL.</p>
 */
@Repository
@RequiredArgsConstructor
public class InvestmentStatisticsRepository {

    private static final String LISTINGS_BY_STATUS_SQL = """
            SELECT status,
                   COUNT(*) AS listings,
                   COALESCE(SUM(target_amount), 0) AS target_amount,
                   COALESCE(SUM(committed_amount), 0) AS committed_amount
            FROM market_listings
            GROUP BY status
            """;

    private static final String NOTES_BY_STATUS_SQL = """
            SELECT status,
                   COUNT(*) AS notes,
                   COALESCE(SUM(outstanding_principal), 0) AS outstanding_principal
            FROM investment_notes
            GROUP BY status
            """;

    private static final String ACTIVE_AUTO_INVEST_SQL = """
            SELECT COUNT(*) FROM auto_invest_configs WHERE enabled
            """;

    private static final String COMMITMENTS_SERIES_SQL = """
            SELECT date_trunc(:unit, created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) AS commitments,
                   COALESCE(SUM(amount), 0) AS committed_amount
            FROM investment_commitments
            WHERE created_at >= :startInclusive AND created_at < :endExclusive
              AND status <> :cancelled
            GROUP BY 1
            """;

    private static final String FULLY_FUNDED_SERIES_SQL = """
            SELECT date_trunc(:unit, fully_funded_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) AS listings
            FROM market_listings
            WHERE fully_funded_at >= :startInclusive AND fully_funded_at < :endExclusive
            GROUP BY 1
            """;

    // Trả tổng giá × khối lượng và tổng khối lượng thay vì AVG: giá bình quân phải có trọng số theo số
    // Note và làm tròn một lần ở Java. Ép bigint trước khi nhân để không tràn int khi gộp nhiều lần khớp.
    // Nhóm "performing" (Note chưa nợ xấu lúc khớp, cờ defaulted chụp ở trade) tính bằng FILTER trong cùng
    // câu: giá Note nợ xấu thấp hẳn nên trộn vào sẽ kéo giá bình quân xuống; cùng câu để hai nhóm cùng một
    // ảnh dữ liệu và không thêm lượt quét bảng.
    private static final String TRADES_SERIES_SQL = """
            SELECT date_trunc(:unit, executed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) AS trades,
                   COALESCE(SUM(amount), 0) AS traded_amount,
                   COALESCE(SUM(platform_fee), 0) AS platform_fee,
                   COALESCE(SUM(price_permille::bigint * quantity), 0) AS price_quantity,
                   COALESCE(SUM(quantity::bigint), 0) AS quantity,
                   COUNT(*) FILTER (WHERE NOT defaulted) AS performing_trades,
                   COALESCE(SUM(price_permille::bigint * quantity) FILTER (WHERE NOT defaulted), 0)
                       AS performing_price_quantity,
                   COALESCE(SUM(quantity::bigint) FILTER (WHERE NOT defaulted), 0) AS performing_quantity
            FROM order_book_trades
            WHERE executed_at >= :startInclusive AND executed_at < :endExclusive
              AND settlement_status <> :failed
            GROUP BY 1
            """;

    private static final String AUTO_INVEST_SERIES_SQL = """
            SELECT date_trunc(:unit, created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) FILTER (WHERE outcome = :matched) AS placed,
                   COUNT(*) FILTER (WHERE outcome = :skipped) AS skipped
            FROM auto_invest_matches
            WHERE created_at >= :startInclusive AND created_at < :endExclusive
            GROUP BY 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** Số listing và tổng mục tiêu/vốn đã gom theo từng trạng thái; chỉ trạng thái có dữ liệu. */
    public List<ListingStatusRow> listingsByStatus() {
        return jdbcTemplate.query(LISTINGS_BY_STATUS_SQL, (rs, rowNum) -> new ListingStatusRow(
                rs.getString("status"),
                rs.getLong("listings"),
                rs.getBigDecimal("target_amount"),
                rs.getBigDecimal("committed_amount")));
    }

    /** Số Note và tổng dư nợ gốc còn lại theo từng trạng thái; chỉ trạng thái có dữ liệu. */
    public List<NoteStatusRow> notesByStatus() {
        return jdbcTemplate.query(NOTES_BY_STATUS_SQL, (rs, rowNum) -> new NoteStatusRow(
                rs.getString("status"),
                rs.getLong("notes"),
                rs.getBigDecimal("outstanding_principal")));
    }

    public long countActiveAutoInvestConfigs() {
        Long count = jdbcTemplate.queryForObject(ACTIVE_AUTO_INVEST_SQL, new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    public List<CommitmentBucketRow> commitmentsByBucket(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("cancelled", CommitmentStatus.CANCELLED.name());
        return jdbcTemplate.query(COMMITMENTS_SERIES_SQL, params, (rs, rowNum) -> new CommitmentBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("commitments"),
                rs.getBigDecimal("committed_amount")));
    }

    public List<CountBucketRow> fullyFundedListingsByBucket(StatisticsPeriod period) {
        return jdbcTemplate.query(FULLY_FUNDED_SERIES_SQL, periodParams(period), (rs, rowNum) -> new CountBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("listings")));
    }

    public List<TradeBucketRow> tradesByBucket(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("failed", SettlementStatus.FAILED.name());
        return jdbcTemplate.query(TRADES_SERIES_SQL, params, (rs, rowNum) -> new TradeBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("trades"),
                rs.getBigDecimal("traded_amount"),
                rs.getBigDecimal("platform_fee"),
                rs.getLong("price_quantity"),
                rs.getLong("quantity"),
                rs.getLong("performing_trades"),
                rs.getLong("performing_price_quantity"),
                rs.getLong("performing_quantity")));
    }

    public List<AutoInvestBucketRow> autoInvestByBucket(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("matched", AutoInvestMatch.Outcome.MATCHED.name())
                .addValue("skipped", AutoInvestMatch.Outcome.SKIPPED.name());
        return jdbcTemplate.query(AUTO_INVEST_SERIES_SQL, params, (rs, rowNum) -> new AutoInvestBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("placed"),
                rs.getLong("skipped")));
    }

    /**
     * Đơn vị cột truyền dạng tham số ({@code date_trunc} nhận {@code text}); mốc thời gian truyền
     * {@link OffsetDateTime} UTC vì driver PostgreSQL không bind trực tiếp {@code Instant}.
     */
    private static MapSqlParameterSource periodParams(StatisticsPeriod period) {
        return new MapSqlParameterSource()
                .addValue("unit", period.bucket().sqlUnit())
                .addValue("startInclusive", OffsetDateTime.ofInstant(period.startInclusive(), ZoneOffset.UTC))
                .addValue("endExclusive", OffsetDateTime.ofInstant(period.endExclusive(), ZoneOffset.UTC));
    }

    public record ListingStatusRow(String status, long listings, BigDecimal targetAmount, BigDecimal committedAmount) {
    }

    public record NoteStatusRow(String status, long notes, BigDecimal outstandingPrincipal) {
    }

    public record CommitmentBucketRow(LocalDate bucketStart, long commitments, BigDecimal committedAmount) {
    }

    public record CountBucketRow(LocalDate bucketStart, long count) {
    }

    /**
     * @param priceQuantity           tổng {@code price_permille × quantity}
     * @param quantity                tổng số Note đã khớp
     * @param performingTrades        số lần khớp của Note chưa nợ xấu ({@code defaulted = false})
     * @param performingPriceQuantity tổng {@code price_permille × quantity} của các lần khớp đó
     * @param performingQuantity      tổng số Note của các lần khớp đó
     */
    public record TradeBucketRow(
            LocalDate bucketStart,
            long trades,
            BigDecimal tradedAmount,
            BigDecimal platformFee,
            long priceQuantity,
            long quantity,
            long performingTrades,
            long performingPriceQuantity,
            long performingQuantity
    ) {
    }

    public record AutoInvestBucketRow(LocalDate bucketStart, long placed, long skipped) {
    }
}
