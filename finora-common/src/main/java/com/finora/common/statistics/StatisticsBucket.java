package com.finora.common.statistics;

import com.finora.common.exception.BusinessException;
import java.time.DayOfWeek;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Độ rộng một cột trong chuỗi thống kê quản trị.
 * Tuần bắt đầu thứ Hai để khớp {@code date_trunc('week', ...)} của PostgreSQL.
 */
public enum StatisticsBucket {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String sqlUnit;

    StatisticsBucket(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    /**
     * Đọc tham số {@code bucket} của API (không phân biệt hoa thường); rỗng nghĩa là dùng mặc định.
     *
     * @throws BusinessException 400 {@value StatisticsPeriod#INVALID_RANGE_CODE} khi không phải DAY, WEEK, MONTH
     */
    public static StatisticsBucket parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, StatisticsPeriod.INVALID_RANGE_CODE,
                    "Tham số bucket chỉ nhận DAY, WEEK hoặc MONTH");
        }
    }

    /** Đơn vị truyền vào {@code date_trunc}; chỉ lấy từ enum nên an toàn khi ghép vào câu SQL. */
    public String sqlUnit() {
        return sqlUnit;
    }

    /** Ngày đầu của cột chứa {@code date}. */
    public LocalDate startOf(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    /** Ngày đầu của cột kế tiếp. */
    public LocalDate next(LocalDate bucketStart) {
        return switch (this) {
            case DAY -> bucketStart.plusDays(1);
            case WEEK -> bucketStart.plusWeeks(1);
            case MONTH -> bucketStart.plusMonths(1);
        };
    }
}
