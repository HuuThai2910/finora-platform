package com.finora.investment.dto.request;

import com.finora.investment.exception.InvestmentDomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Bộ lọc nâng cao của danh sách khoản vay trên sàn (bảng lọc trang Gọi vốn của web quản trị). Mọi trường tùy chọn;
 * {@code null} là không lọc. Kết hợp AND với các tham số cũ ({@code status}, {@code grade}, {@code minRate},
 * {@code maxTermMonths}, {@code q}) mà app nhà đầu tư đang gọi.
 *
 * @param amountMin        số tiền gọi vốn ({@code targetAmount}) tối thiểu
 * @param fundedPercentMin tiến độ đã gọi (%) = vốn đã cam kết / số tiền gọi × 100
 * @param rateMin          lãi suất năm (%)
 * @param termMin          kỳ hạn (tháng)
 * @param grades           hạng tín dụng (khớp một trong các hạng); bản nhiều lựa chọn của tham số {@code grade}
 * @param scoreMin         điểm tín dụng lúc niêm yết
 * @param purposeCode      mã mục đích vay ({@code LoanPurpose} của finora-loan)
 * @param closesFrom       hạn gọi vốn ({@code fundingClosesAt}) từ ngày, giờ Việt Nam, gồm cả ngày này
 * @param openedFrom       ngày mở gọi vốn ({@code fundingOpenedAt}) từ ngày, giờ Việt Nam
 */
public record ListingAdvancedFilter(
        BigDecimal amountMin,
        BigDecimal amountMax,
        BigDecimal fundedPercentMin,
        BigDecimal fundedPercentMax,
        BigDecimal rateMin,
        BigDecimal rateMax,
        Integer termMin,
        Integer termMax,
        List<String> grades,
        Integer scoreMin,
        Integer scoreMax,
        String purposeCode,
        LocalDate closesFrom,
        LocalDate closesTo,
        LocalDate openedFrom,
        LocalDate openedTo) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static final ListingAdvancedFilter NONE = new ListingAdvancedFilter(null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    public ListingAdvancedFilter {
        grades = grades == null ? List.of() : grades.stream()
                .filter(grade -> grade != null && !grade.isBlank())
                .map(grade -> grade.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        purposeCode = purposeCode == null || purposeCode.isBlank() ? null : purposeCode.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isEmpty() {
        return amountMin == null && amountMax == null && fundedPercentMin == null && fundedPercentMax == null
                && rateMin == null && rateMax == null && termMin == null && termMax == null && grades.isEmpty()
                && scoreMin == null && scoreMax == null && purposeCode == null && closesFrom == null
                && closesTo == null && openedFrom == null && openedTo == null;
    }

    /** Khoảng sai trả 400 {@code FILTER_RANGE_INVALID}; số âm hoặc tiến độ ngoài 0–100 trả 400 {@code FILTER_VALUE_INVALID}. */
    public ListingAdvancedFilter validate() {
        requireNonNegative(amountMin, "Số tiền từ");
        requireNonNegative(amountMax, "Số tiền đến");
        requireNonNegative(rateMin, "Lãi suất từ");
        requireNonNegative(rateMax, "Lãi suất đến");
        requireNonNegative(termMin, "Kỳ hạn từ");
        requireNonNegative(termMax, "Kỳ hạn đến");
        requireNonNegative(scoreMin, "Điểm từ");
        requireNonNegative(scoreMax, "Điểm đến");
        requirePercent(fundedPercentMin, "Tiến độ từ");
        requirePercent(fundedPercentMax, "Tiến độ đến");
        requireOrdered(amountMin, amountMax, "Số tiền gọi vốn");
        requireOrdered(fundedPercentMin, fundedPercentMax, "Tiến độ đã gọi");
        requireOrdered(rateMin, rateMax, "Lãi suất");
        requireOrdered(termMin, termMax, "Kỳ hạn");
        requireOrdered(scoreMin, scoreMax, "Điểm");
        requireOrdered(closesFrom, closesTo, "Hạn gọi vốn");
        requireOrdered(openedFrom, openedTo, "Ngày mở gọi vốn");
        return this;
    }

    private static void requireNonNegative(Number value, String label) {
        if (value != null && new BigDecimal(value.toString()).signum() < 0) {
            throw InvestmentDomainException.invalidInput("FILTER_VALUE_INVALID", label + " không được âm");
        }
    }

    private static void requirePercent(BigDecimal value, String label) {
        if (value != null && (value.signum() < 0 || value.compareTo(HUNDRED) > 0)) {
            throw InvestmentDomainException.invalidInput("FILTER_VALUE_INVALID", label + " phải nằm trong 0–100");
        }
    }

    private static <T extends Comparable<? super T>> void requireOrdered(T min, T max, String label) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw InvestmentDomainException.invalidInput("FILTER_RANGE_INVALID",
                    label + ": giá trị \"từ\" phải nhỏ hơn hoặc bằng giá trị \"đến\"");
        }
    }
}
