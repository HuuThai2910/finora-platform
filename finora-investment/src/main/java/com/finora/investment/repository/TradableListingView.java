package com.finora.investment.repository;

import java.math.BigDecimal;

/** Một khoản gọi vốn còn phần vốn lưu hành: số phần vốn, tổng dư nợ gốc và số phần vốn đang nợ xấu. */
public record TradableListingView(Long listingId, Long noteCount, BigDecimal outstandingPrincipal, Long defaultedCount) {
}
