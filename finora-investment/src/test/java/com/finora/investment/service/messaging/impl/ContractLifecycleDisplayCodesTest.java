package com.finora.investment.service.messaging.impl;

import com.finora.investment.domain.listing.entity.MarketListing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mã hiển thị Loan gửi kèm event chỉ bổ sung khi listing chưa có, event cũ không có mã thì không xóa mã đã nhận. */
class ContractLifecycleDisplayCodesTest {

    @Test
    void fillsMissingCodesOnce() {
        MarketListing listing = MarketListing.builder().applicationNumber("LA-10").build();

        ContractLifecycleEventHandlerImpl.fillDisplayCodes(listing, "HS-20261109-3RXA", "HĐ-20261110-K7QM");
        ContractLifecycleEventHandlerImpl.fillDisplayCodes(listing, "HS-OTHER", "HĐ-OTHER");

        assertThat(listing.getApplicationCode()).isEqualTo("HS-20261109-3RXA");
        assertThat(listing.getContractCode()).isEqualTo("HĐ-20261110-K7QM");
    }

    @Test
    void eventWithoutCodesKeepsListingUnchanged() {
        MarketListing listing = MarketListing.builder().applicationNumber("LA-10").build();

        ContractLifecycleEventHandlerImpl.fillDisplayCodes(listing, null, " ");

        assertThat(listing.getApplicationCode()).isNull();
        assertThat(listing.getContractCode()).isNull();
    }
}
