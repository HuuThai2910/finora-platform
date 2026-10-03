package com.finora.investment.service.orderbook;

import com.finora.investment.exception.InvestmentDomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Công thức tiền của sổ lệnh. Gom một chỗ để lúc giữ tiền và lúc khớp chắc chắn dùng cùng cách
 * làm tròn — lệch nhau một đồng là lệnh mua có thể chi vượt số đã giữ.
 */
public final class OrderBookPricing {

    /**
     * Phí chuyển nhượng 5% trên giá bán, trừ của người bán.
     *
     * <p>Là policy nghiệp vụ của bản demo, <strong>không</strong> phải quy định pháp luật (INV-E1
     * mục 3). Trừ của người bán để người mua trả đúng giá đặt và tổng tiền họ bỏ ra không vượt dư
     * nợ gốc.</p>
     */
    static final BigDecimal FEE_RATE = new BigDecimal("0.05");

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal PERMILLE = BigDecimal.valueOf(1000);

    private OrderBookPricing() {
    }

    /** Đổi giá % (một chữ số thập phân) sang phần nghìn để lưu và so sánh bằng số nguyên. */
    public static int toPermille(BigDecimal pricePercent) {
        BigDecimal permille = pricePercent.movePointRight(1);
        if (permille.stripTrailingZeros().scale() > 0) {
            throw InvestmentDomainException.invalidInput(
                    "ORDER_PRICE_TICK", "Giá chỉ được đặt theo bước 0,1%");
        }
        int value = permille.intValueExact();
        if (value < 1 || value > 1000) {
            throw InvestmentDomainException.invalidInput(
                    "ORDER_PRICE_OUT_OF_RANGE", "Giá phải từ 0,1% tới 100% dư nợ gốc");
        }
        return value;
    }

    public static String toPercent(int permille) {
        return BigDecimal.valueOf(permille, 1).toPlainString();
    }

    /**
     * Tiền của một Note ở giá {@code permille}: dư nợ gốc × giá, làm tròn HALF_UP tới đồng lẻ.
     *
     * <p>Hàm đơn điệu: dư nợ nhỏ hơn hoặc giá thấp hơn không bao giờ cho số lớn hơn. Đó là lý do
     * tiền giữ lúc đặt lệnh mua luôn đủ cho mọi lần khớp sau.</p>
     */
    public static BigDecimal amountForNote(BigDecimal outstanding, int permille) {
        return outstanding.multiply(BigDecimal.valueOf(permille))
                .divide(PERMILLE, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Phí nền tảng làm tròn xuống, để người bán không bị trừ thừa một đồng vì làm tròn. */
    public static BigDecimal platformFee(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    /**
     * Tiền cần giữ cho một lệnh mua: mỗi Note tính theo dư nợ lớn nhất hiện có của đợt gọi vốn,
     * vì lệnh mua chưa biết sẽ khớp vào Note nào. Dư nợ chỉ giảm và giá khớp không cao hơn giá đặt,
     * nên số này là trần của mọi lần khớp.
     */
    public static BigDecimal holdAmount(BigDecimal maxOutstanding, int permille, int quantity) {
        return amountForNote(maxOutstanding, permille).multiply(BigDecimal.valueOf(quantity));
    }
}
