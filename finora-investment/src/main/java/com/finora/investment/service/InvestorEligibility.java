package com.finora.investment.service;

import com.finora.investment.domain.listing.entity.MarketListing;

/**
 * Điều kiện để một người dùng được bỏ tiền vào khoản vay, ngoài vai trò INVESTOR đã chặn ở
 * {@code SecurityConfig}.
 */
public interface InvestorEligibility {

    /**
     * Người dùng đang gọi API đã eKYC. Không có JWT người dùng (lời gọi nội bộ trong worker) thì bỏ
     * qua vì không có người dùng nào để kiểm.
     */
    void requireVerifiedInvestor();

    /** Chặn người vay tự góp vốn/mua Note khoản vay của chính mình. */
    void requireNotBorrower(MarketListing listing, String investorId);
}
