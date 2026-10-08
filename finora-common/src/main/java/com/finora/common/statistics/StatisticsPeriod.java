package com.finora.common.statistics;

import com.finora.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * Khoảng thời gian của một chuỗi thống kê quản trị, đã nới ra cho tròn cột.
 *
 * <p>Ngày được hiểu theo giờ Việt Nam vì quản trị viên đọc "hồ sơ nộp hôm nay" theo lịch ở Việt Nam;
 * dữ liệu trong DB vẫn là UTC nên câu SQL phải đổi múi giờ trước khi cắt cột. Ví dụ hồ sơ nộp lúc
 * 23:30 UTC ngày 1/10 là 6:30 sáng 2/10 ở Việt Nam và phải rơi vào cột 2/10.</p>
 *
 * <p>{@code from} được lùi về đầu cột và {@code to} được kéo tới cuối cột, để cột đầu và cột cuối không
 * bị đếm thiếu một phần (chọn theo tháng từ 15/9 thì cột tháng 9 vẫn đủ cả tháng).</p>
 */
public record StatisticsPeriod(LocalDate from, LocalDate to, StatisticsBucket bucket, ZoneId zone) {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Giới hạn số cột: đủ cho một năm theo ngày, chặn truy vấn quét cả bảng do nhập nhầm khoảng. */
    public static final int MAX_BUCKETS = 366;

    public static final int DEFAULT_DAYS = 30;

    public static final String INVALID_RANGE_CODE = "STATISTICS_RANGE_INVALID";

    /**
     * Dựng khoảng từ tham số API; tham số thiếu thì lấy 30 ngày gần nhất tính đến hôm nay theo giờ Việt Nam.
     *
     * @throws BusinessException 400 {@value #INVALID_RANGE_CODE} khi from sau to hoặc quá {@value #MAX_BUCKETS} cột
     */
    public static StatisticsPeriod of(LocalDate from, LocalDate to, StatisticsBucket bucket, Clock clock) {
        StatisticsBucket effectiveBucket = bucket == null ? StatisticsBucket.DAY : bucket;
        LocalDate end = to == null ? LocalDate.now(clock.withZone(VIETNAM)) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_RANGE_CODE, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        LocalDate alignedStart = effectiveBucket.startOf(start);
        LocalDate alignedEnd = effectiveBucket.next(effectiveBucket.startOf(end)).minusDays(1);
        StatisticsPeriod period = new StatisticsPeriod(alignedStart, alignedEnd, effectiveBucket, VIETNAM);
        if (period.bucketStarts().size() > MAX_BUCKETS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_RANGE_CODE,
                    "Khoảng thống kê quá dài, tối đa " + MAX_BUCKETS + " cột");
        }
        return period;
    }

    /**
     * Đọc tham số ngày {@code YYYY-MM-DD} của API thống kê; rỗng nghĩa là dùng mặc định.
     * Controller nhận chuỗi rồi gọi hàm này thay vì để Spring đổi kiểu: lỗi đổi kiểu rơi vào handler chung
     * thành 500, còn web cần 400 kèm mã để hiện lỗi ngay trong ô biểu đồ.
     */
    public static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_RANGE_CODE,
                    "Tham số " + name + " phải có dạng YYYY-MM-DD");
        }
    }

    /** Mốc bắt đầu (bao gồm) để lọc cột thời gian trong DB. */
    public Instant startInclusive() {
        return from.atStartOfDay(zone).toInstant();
    }

    /** Mốc kết thúc (không bao gồm): đầu ngày sau {@code to}. */
    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** Ngày đầu của từng cột, theo thứ tự thời gian. */
    public List<LocalDate> bucketStarts() {
        List<LocalDate> starts = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = bucket.next(cursor)) {
            starts.add(cursor);
            if (starts.size() > MAX_BUCKETS) {
                break;
            }
        }
        return Collections.unmodifiableList(starts);
    }

    /**
     * Trải chuỗi đủ mọi cột: SQL {@code GROUP BY} chỉ trả cột có dữ liệu, nhưng biểu đồ cần cả cột bằng 0
     * để không nối liền hai ngày cách xa nhau như thể liên tiếp.
     */
    public <T> List<T> dense(Map<LocalDate, T> rowsByBucket, Function<LocalDate, T> empty) {
        return bucketStarts().stream()
                .map(start -> rowsByBucket.containsKey(start) ? rowsByBucket.get(start) : empty.apply(start))
                .toList();
    }
}
