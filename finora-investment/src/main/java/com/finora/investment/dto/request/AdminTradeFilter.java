package com.finora.investment.dto.request;

import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.orderbook.enums.TradeProgress;
import com.finora.investment.exception.InvestmentDomainException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bộ lọc bảng lần khớp của Chợ chuyển nhượng quản trị. Mọi trường tùy chọn; {@code null} là không lọc.
 *
 * @param listingId    chỉ lần khớp của một sổ (khoản gọi vốn)
 * @param settlement   trạng thái thanh toán (tham số cũ, còn giữ cho link sẵn có)
 * @param progress     chặng gộp: chờ thanh toán, đang lập giấy xác nhận, hoàn tất
 * @param q            mã lần khớp hoặc mã hồ sơ/hợp đồng của khoản vay, khớp một phần
 * @param party        một phần họ tên người bán hoặc người mua (tra ID qua User)
 * @param executedFrom ngày khớp từ, giờ Việt Nam, gồm cả ngày này
 * @param amountMin    số tiền bên mua trả (đồng)
 */
public record AdminTradeFilter(
        Long listingId,
        SettlementStatus settlement,
        TradeProgress progress,
        String q,
        String party,
        LocalDate executedFrom,
        LocalDate executedTo,
        BigDecimal amountMin,
        BigDecimal amountMax) {

    public static final AdminTradeFilter NONE =
            new AdminTradeFilter(null, null, null, null, null, null, null, null, null);

    /** Số tiền âm hoặc khoảng ngược trả 400, cùng mã lỗi với bảng lọc trang Gọi vốn. */
    public AdminTradeFilter validate() {
        if ((amountMin != null && amountMin.signum() < 0) || (amountMax != null && amountMax.signum() < 0)) {
            throw InvestmentDomainException.invalidInput("FILTER_VALUE_INVALID", "Số tiền không được âm");
        }
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw InvestmentDomainException.invalidInput("FILTER_RANGE_INVALID",
                    "Số tiền: giá trị từ phải nhỏ hơn hoặc bằng giá trị đến");
        }
        if (executedFrom != null && executedTo != null && executedFrom.isAfter(executedTo)) {
            throw InvestmentDomainException.invalidInput("FILTER_RANGE_INVALID",
                    "Ngày khớp: ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        return this;
    }
}
