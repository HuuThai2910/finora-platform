package com.finora.investment.repository;

/**
 * Số nhà đầu tư (không trùng) của một listing, đọc theo lô cho danh sách gọi vốn.
 */
public record ListingInvestorCountView(Long listingId, Long investorCount) {
}
