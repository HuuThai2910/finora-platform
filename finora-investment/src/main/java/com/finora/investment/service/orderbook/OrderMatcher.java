package com.finora.investment.service.orderbook;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.OrderSide;
import java.util.ArrayList;
import java.util.List;

/**
 * Thuật toán khớp thuần: không đọc database, không đổi trạng thái — chỉ quyết định lệnh mới khớp
 * với lệnh nào, bao nhiêu Note. Tách riêng để kiểm thử thứ tự ưu tiên mà không cần PostgreSQL.
 *
 * <p>Quy tắc:</p>
 * <ul>
 *   <li><b>Ưu tiên giá rồi thời gian.</b> Danh sách lệnh nằm chờ truyền vào đã được sắp theo thứ
 *       tự đó; thuật toán đi lần lượt từ đầu.</li>
 *   <li><b>Giá khớp là giá của lệnh nằm chờ.</b> Người đặt lệnh mới được hưởng giá tốt hơn giá
 *       mình đặt nếu sổ có sẵn — chuẩn của sàn khớp lệnh liên tục.</li>
 *   <li><b>Khớp từng phần.</b> Được bao nhiêu khớp bấy nhiêu; phần còn lại của lệnh mới nằm chờ.</li>
 *   <li><b>Chặn tự khớp.</b> Gặp lệnh của chính người đặt thì dừng; phần còn lại của lệnh mới bị
 *       huỷ, lệnh cũ giữ nguyên. Không nhảy qua lệnh của mình để khớp tiếp, vì như vậy phá thứ tự
 *       ưu tiên giá của người khác đứng sau.</li>
 * </ul>
 */
public final class OrderMatcher {

    private OrderMatcher() {
    }

    /** Một lần khớp dự kiến với một lệnh nằm chờ. */
    public record Fill(BookOrder resting, int quantity) {
    }

    /**
     * Kết quả lập kế hoạch khớp.
     *
     * @param selfTradeStopped dừng vì chạm lệnh của chính người đặt
     */
    public record MatchPlan(List<Fill> fills, boolean selfTradeStopped) {
    }

    /**
     * @param incoming       lệnh mới vào sổ
     * @param crossingResting lệnh phía đối ứng khớp được giá với lệnh mới, đã sắp theo ưu tiên
     */
    public static MatchPlan plan(BookOrder incoming, List<BookOrder> crossingResting) {
        int remaining = incoming.remainingQuantity();
        List<Fill> fills = new ArrayList<>();

        for (BookOrder resting : crossingResting) {
            if (remaining == 0) {
                break;
            }
            if (resting.getSide() == incoming.getSide() || !crosses(incoming, resting)) {
                // Truy vấn đã lọc đúng; gặp ở đây nghĩa là sai lập trình, không phải tình huống nghiệp vụ.
                throw new IllegalArgumentException("Lệnh " + resting.getOrderReference() + " không khớp được giá");
            }
            if (resting.getInvestorId().equals(incoming.getInvestorId())) {
                return new MatchPlan(fills, true);
            }
            int quantity = Math.min(remaining, resting.remainingQuantity());
            fills.add(new Fill(resting, quantity));
            remaining -= quantity;
        }
        return new MatchPlan(fills, false);
    }

    /** Lệnh mua khớp được lệnh bán khi giá mua không thấp hơn giá bán. */
    static boolean crosses(BookOrder incoming, BookOrder resting) {
        return incoming.getSide() == OrderSide.BID
                ? incoming.getPricePermille() >= resting.getPricePermille()
                : incoming.getPricePermille() <= resting.getPricePermille();
    }
}
