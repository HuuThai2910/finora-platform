package com.finora.investment.dto.request;

import com.finora.investment.domain.orderbook.enums.MarketBookState;
import com.finora.investment.exception.InvestmentDomainException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Bộ lọc danh sách sổ lệnh của Chợ chuyển nhượng quản trị. Mọi trường tùy chọn; {@code null} là không lọc.
 *
 * @param q      mã hồ sơ/hợp đồng (HS-/HĐ- hoặc mã tra cứu) hoặc một phần họ tên người vay
 * @param grades hạng tín dụng, khớp một trong các hạng
 * @param sort   thứ tự; {@code null} là khớp gần nhất trước
 */
public record AdminOrderBookFilter(
        String q,
        MarketBookState state,
        List<String> grades,
        BigDecimal rateMin,
        BigDecimal rateMax,
        Sort sort) {

    /** Thứ tự danh sách: khớp gần nhất trước, nhiều phần vốn đang chờ khớp nhất, lãi suất cao nhất. */
    public enum Sort {
        RECENT,
        DEPTH,
        RATE
    }

    public static final AdminOrderBookFilter NONE = new AdminOrderBookFilter(null, null, null, null, null, null);

    public AdminOrderBookFilter {
        grades = grades == null ? List.of() : grades.stream()
                .filter(grade -> grade != null && !grade.isBlank())
                .map(grade -> grade.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        sort = sort == null ? Sort.RECENT : sort;
    }

    /** Lãi suất âm hoặc "từ" lớn hơn "đến" trả 400, cùng mã lỗi với bảng lọc trang Gọi vốn. */
    public AdminOrderBookFilter validate() {
        if ((rateMin != null && rateMin.signum() < 0) || (rateMax != null && rateMax.signum() < 0)) {
            throw InvestmentDomainException.invalidInput("FILTER_VALUE_INVALID", "Lãi suất không được âm");
        }
        if (rateMin != null && rateMax != null && rateMin.compareTo(rateMax) > 0) {
            throw InvestmentDomainException.invalidInput("FILTER_RANGE_INVALID",
                    "Lãi suất: giá trị từ phải nhỏ hơn hoặc bằng giá trị đến");
        }
        return this;
    }
}
