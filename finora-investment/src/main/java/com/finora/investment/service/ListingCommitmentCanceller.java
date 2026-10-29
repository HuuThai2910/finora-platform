package com.finora.investment.service;

import com.finora.investment.domain.listing.MarketListing;

import java.time.Instant;
import java.util.List;

/**
 * Hủy toàn bộ phần vốn đang ACTIVE của một listing và lệnh tương ứng.
 */
public interface ListingCommitmentCanceller {

    /**
     * Phần vốn vừa hủy.
     *
     * @param count       số phần vốn vừa hủy; tổng vốn đã cam kết của listing giảm tương ứng
     * @param investorIds nhà đầu tư có phần vốn vừa hủy, không trùng, sắp theo thứ tự chữ (người nhận thông báo, P6)
     */
    record CancelledCommitments(int count, List<String> investorIds) {
        public static final CancelledCommitments NONE = new CancelledCommitments(0, List.of());
    }

    CancelledCommitments cancelActive(MarketListing listing, String reasonCode, String reasonDetail, String actor,
            Instant now);
}
