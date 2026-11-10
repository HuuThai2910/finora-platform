package com.finora.investment.service;

import com.finora.investment.domain.listing.entity.MarketListing;

import java.time.Instant;
import java.util.List;

/**
 * Hủy toàn bộ phần vốn đang ACTIVE của một listing và lệnh tương ứng.
 */
public interface ListingCommitmentCanceller {

    /** Mã lý do trên lệnh khi listing hết hạn gọi vốn mà chưa đủ vốn. */
    String REASON_LISTING_EXPIRED = "LISTING_EXPIRED";

    /** Mã lý do trên lệnh khi Loan hủy đợt gọi vốn (rút hồ sơ, từ chối/hết hạn ký, giải ngân thất bại). */
    String REASON_LOAN_FUNDING_CANCELLED = "LOAN_FUNDING_CANCELLED";

    /**
     * Hai mã lý do đóng/hủy cả listing. Phần vốn hủy với các mã này đã được tính trong số tiền đã gọi được
     * ({@code raisedAmount}); phần vốn rời đi khi gọi vốn bổ sung hay nhà đầu tư tự hủy thì không.
     */
    List<String> LISTING_CLOSING_REASONS = List.of(REASON_LISTING_EXPIRED, REASON_LOAN_FUNDING_CANCELLED);

    /**
     * Phần vốn vừa hủy.
     *
     * @param count       số phần vốn vừa hủy; tổng vốn đã cam kết của listing giảm tương ứng
     * @param investorIds nhà đầu tư có phần vốn vừa hủy, không trùng, sắp theo thứ tự chữ (người nhận thông báo, P6)
     */
    record CancelledCommitments(int count, List<String> investorIds) {
        public static final CancelledCommitments NONE = new CancelledCommitments(0, List.of());
    }

    /**
     * Hủy phần vốn ACTIVE, trừ tiến độ và ghi lại số tiền đã gọi được ngay trước khi trừ ({@code raisedAmount}), vì
     * sau bước này {@code committedAmount} chỉ còn phần đã khóa (thường là 0) và không còn cho biết đã gọi được bao
     * nhiêu. Caller phải khóa listing và chỉ gọi khi đóng/hủy cả listing.
     */
    CancelledCommitments cancelActive(MarketListing listing, String reasonCode, String reasonDetail, String actor,
            Instant now);
}
