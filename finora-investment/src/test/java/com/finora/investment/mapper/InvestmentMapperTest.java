package com.finora.investment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.dto.response.MarketListingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvestmentMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    /** App dùng mã hồ sơ này để tải hồ sơ người vay từ Loan; thiếu nó thì thẻ hồ sơ không hiện. */
    @Test
    void listingResponseCarriesApplicationNumber() {
        MarketListing listing = MarketListing.builder()
                .id(5L).loanId(77L).applicationNumber("LA-0123456789ABCDEF0123")
                .productCode("SP-01").purpose("Chi phí giáo dục").region("Không công bố")
                .creditGrade("B").creditScore(80)
                .targetAmount(new BigDecimal("10000000.00")).committedAmount(new BigDecimal("4000000.00"))
                .annualInterestRate(new BigDecimal("15.0000")).termMonths(12).repaymentMethod("ANNUITY")
                .noteDenomination(new BigDecimal("1000000.00")).minInvestmentAmount(new BigDecimal("1000000.00"))
                .status(ListingStatus.OPEN).fundingRound(1)
                .fundingOpenedAt(NOW).fundingClosesAt(NOW.plusSeconds(86_400))
                .build();

        MarketListingResponse response = new InvestmentMapper().toListingResponse(listing);

        assertThat(response.applicationNumber()).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(response.loanId()).isEqualTo(77L);
        assertThat(response.remainingAmount()).isEqualTo("6000000.00");
    }
}
