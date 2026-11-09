package com.finora.loan.dto.decision.request;

import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.exception.LoanBusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Bộ lọc nâng cao của danh sách hồ sơ quản trị (bảng lọc của trang Quản lý khoản vay). Mọi trường tùy chọn;
 * {@code null} là không lọc theo tiêu chí đó. Kết hợp AND với {@code status}, {@code servicing}, {@code q},
 * {@code borrowerId}.
 *
 * @param submittedFrom ngày nộp từ (gồm cả ngày này, theo giờ Việt Nam)
 * @param submittedTo   ngày nộp đến (gồm cả ngày này, theo giờ Việt Nam)
 * @param amountMin     số tiền đề nghị vay tối thiểu (VND)
 * @param termMin       kỳ hạn đề nghị tối thiểu (tháng)
 * @param scoreMin      điểm đánh giá (thang 100) của lần chấm mới nhất, tối thiểu
 * @param grades        hạng tín dụng của lần chấm mới nhất (khớp một trong các hạng)
 * @param rateMin       lãi suất năm (%) — lãi cuối nếu đã định giá, chưa có thì lãi của sản phẩm lúc nộp
 * @param productCode   mã sản phẩm vay lúc nộp
 * @param purposeCode   mục đích vay
 */
public record AdminApplicationFilter(
        LocalDate submittedFrom,
        LocalDate submittedTo,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Integer termMin,
        Integer termMax,
        BigDecimal scoreMin,
        BigDecimal scoreMax,
        List<String> grades,
        BigDecimal rateMin,
        BigDecimal rateMax,
        String productCode,
        LoanPurpose purposeCode) {

    /** Điểm đánh giá nằm trong thang 0–100. */
    private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

    /** Không lọc gì thêm. */
    public static final AdminApplicationFilter NONE = new AdminApplicationFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    public AdminApplicationFilter {
        grades = grades == null ? List.of() : grades.stream()
                .filter(grade -> grade != null && !grade.isBlank())
                .map(grade -> grade.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        productCode = productCode == null || productCode.isBlank() ? null : productCode.trim();
    }

    /** Không có tiêu chí nào: danh sách đi đường truy vấn cũ đã được kiểm số query. */
    public boolean isEmpty() {
        return submittedFrom == null && submittedTo == null && amountMin == null && amountMax == null
                && termMin == null && termMax == null && scoreMin == null && scoreMax == null && grades.isEmpty()
                && rateMin == null && rateMax == null && productCode == null && purposeCode == null;
    }

    /**
     * Kiểm khoảng: không âm, điểm trong 0–100, cận dưới không lớn hơn cận trên. Lỗi trả 400
     * {@code FILTER_VALUE_INVALID} hoặc {@code FILTER_RANGE_INVALID}.
     */
    public AdminApplicationFilter validate() {
        requireNonNegative(amountMin, "Số tiền từ");
        requireNonNegative(amountMax, "Số tiền đến");
        requireNonNegative(termMin, "Kỳ hạn từ");
        requireNonNegative(termMax, "Kỳ hạn đến");
        requireNonNegative(rateMin, "Lãi suất từ");
        requireNonNegative(rateMax, "Lãi suất đến");
        requireScore(scoreMin, "Điểm từ");
        requireScore(scoreMax, "Điểm đến");
        requireOrdered(submittedFrom, submittedTo, "Ngày nộp");
        requireOrdered(amountMin, amountMax, "Số tiền vay");
        requireOrdered(termMin, termMax, "Kỳ hạn");
        requireOrdered(scoreMin, scoreMax, "Điểm đánh giá");
        requireOrdered(rateMin, rateMax, "Lãi suất");
        return this;
    }

    private static void requireNonNegative(Number value, String label) {
        if (value != null && new BigDecimal(value.toString()).signum() < 0) {
            throw LoanBusinessException.badRequest("FILTER_VALUE_INVALID", label + " không được âm");
        }
    }

    private static void requireScore(BigDecimal value, String label) {
        if (value != null && (value.signum() < 0 || value.compareTo(MAX_SCORE) > 0)) {
            throw LoanBusinessException.badRequest("FILTER_VALUE_INVALID", label + " phải nằm trong 0–100");
        }
    }

    private static <T extends Comparable<? super T>> void requireOrdered(T min, T max, String label) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw LoanBusinessException.badRequest("FILTER_RANGE_INVALID",
                    label + ": giá trị \"từ\" phải nhỏ hơn hoặc bằng giá trị \"đến\"");
        }
    }
}
