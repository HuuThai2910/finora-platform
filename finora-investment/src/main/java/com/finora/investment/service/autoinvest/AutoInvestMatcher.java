package com.finora.investment.service.autoinvest;

/**
 * Khớp một listing vừa mở với các cấu hình Auto-Invest, theo thứ tự ai bật trước đi trước.
 */
public interface AutoInvestMatcher {

    /** Lý do bỏ qua ghi vào nhật ký; mobile dịch sang tiếng Việt. */
    String ALREADY_INVESTED = "ALREADY_INVESTED";

    String BELOW_MINIMUM = "BELOW_MINIMUM";

    String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    String LISTING_FULL = "LISTING_FULL";

    String LISTING_CLOSED = "LISTING_CLOSED";

    /** Quy tắc chưa xác nhận bản công bố rủi ro hiện hành (Q5): tạm dừng, không đặt lệnh. */
    String RISK_ACKNOWLEDGEMENT_OUTDATED = "RISK_ACKNOWLEDGEMENT_OUTDATED";

    static String idempotencyKey(Long listingId) {
        return "AUTO-" + listingId;
    }

    /**
     * Xét một listing. Trả {@code true} khi đã xét xong và listing được đánh dấu; {@code false}
     * khi phải dừng giữa chừng (Payment không phản hồi, lỗi bất ngờ) để lần quét sau làm tiếp.
     */
    boolean processListing(Long listingId);
}
