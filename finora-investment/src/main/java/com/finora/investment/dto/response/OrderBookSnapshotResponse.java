package com.finora.investment.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Ảnh chụp công khai của một sổ lệnh: độ sâu hai phía gộp theo mức giá và các lần khớp gần nhất.
 *
 * <p>Không chứa mã nhà đầu tư hay mã lệnh: ai đặt lệnh nào là dữ liệu riêng. {@code sequence} tăng
 * mỗi lần sổ thay đổi; client nhận nhiều ảnh qua stream chỉ giữ ảnh có {@code sequence} lớn nhất,
 * vì ảnh có thể đến không đúng thứ tự.</p>
 *
 * @param defaulted             khoản vay đang có Note nợ xấu; đặt lệnh phải xác nhận đã đọc cảnh báo
 * @param referenceOutstanding dư nợ gốc lớn nhất của một Note còn lưu hành trong đợt — cơ sở để
 *                             client hiện số tiền tạm tính (giá × dư nợ) trước khi đặt lệnh; số
 *                             chính thức do backend chốt lúc giữ tiền và lúc khớp. Null khi đợt
 *                             không còn Note lưu hành
 */
public record OrderBookSnapshotResponse(
        Long listingId,
        Long loanId,
        long sequence,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        String noteDenomination,
        String referenceOutstanding,
        boolean defaulted,
        String defaultWarning,
        String bestBidPercent,
        String bestAskPercent,
        String lastTradePercent,
        Instant lastTradeAt,
        List<PriceLevel> bids,
        List<PriceLevel> asks,
        List<TradeTick> recentTrades
) {

    /** Một mức giá: tổng số Note còn chờ khớp và số lệnh ở mức đó. */
    public record PriceLevel(String pricePercent, long quantity, long orderCount) {
    }

    /** Một lần khớp, hiển thị trên băng giá. {@code aggressorSide} là bên chủ động khớp. */
    public record TradeTick(String pricePercent, int quantity, String aggressorSide, Instant executedAt) {
    }
}
