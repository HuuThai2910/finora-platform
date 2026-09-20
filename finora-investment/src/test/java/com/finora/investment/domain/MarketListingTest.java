package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm chứng các hàm tính toán của MarketListing.
 */
class MarketListingTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");
    private static final BigDecimal TARGET = new BigDecimal("100000000.00");
    private static final BigDecimal DENOMINATION = new BigDecimal("1000000.00");

    private MarketListing newListing() {
        return MarketListing.builder()
                .id(1L)
                .loanId(1L)
                .contractNumber("HD-001")
                .productCode("SP-01")
                .purpose("Kinh doanh")
                .region("TP.HCM")
                .creditGrade("B")
                .creditScore(700)
                .targetAmount(TARGET)
                .committedAmount(BigDecimal.ZERO)
                .annualInterestRate(new BigDecimal("0.1500"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .noteDenomination(DENOMINATION)
                .minInvestmentAmount(DENOMINATION)
                .status(ListingStatus.OPEN)
                .fundingRound(1)
                .fundingOpenedAt(NOW)
                .fundingClosesAt(NOW.plus(Duration.ofDays(14)))
                .createdBy("ADMIN-001")
                .updatedBy("ADMIN-001")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("Khởi tạo đúng các trường dữ liệu qua Builder")
    void createsWithBuilder() {
        MarketListing listing = newListing();
        assertThat(listing.getId()).isEqualTo(1L);
        assertThat(listing.getContractNumber()).isEqualTo("HD-001");
        assertThat(listing.getTargetAmount()).isEqualByComparingTo("100000000.00");
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
    }
}
