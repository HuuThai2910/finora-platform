package com.finora.investment.service;

import com.finora.investment.domain.listing.MarketListing;

/**
 * Điều kiện để một người dùng được bỏ tiền vào khoản vay, ngoài vai trò INVESTOR đã chặn ở
 * {@code SecurityConfig}.
 */
public interface InvestorEligibility {

    /**
     * Người dùng đang gọi API đã eKYC. Chạy trong worker (Auto-Invest) thì không có JWT người dùng:
     * điều kiện đã được kiểm lúc nhà đầu tư bật Auto-Invest nên bỏ qua.
     */
    void requireVerifiedInvestor();

    /** Chặn người vay tự góp vốn/mua Note khoản vay của chính mình. */
    void requireNotBorrower(MarketListing listing, String investorId);
}
