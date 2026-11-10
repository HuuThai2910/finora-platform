package com.finora.investment.mapper;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.NoteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    /** App dùng mã hồ sơ này để tải hồ sơ người vay từ Loan; thiếu nó thì thẻ hồ sơ không hiện. */
    @Test
    void listingResponseCarriesApplicationNumber() {
        MarketListing listing = MarketListing.builder()
                .id(5L).loanId(77L).applicationNumber("LA-0123456789ABCDEF0123")
                .productCode("SP-01").purpose("Chi phí giáo dục").purposeCode("EDUCATION").region("Không công bố")
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
        assertThat(response.purposeCode()).isEqualTo("EDUCATION");
        assertThat(response.fundingOpenedAt()).isEqualTo(NOW);
        assertThat(response.raisedAmount()).isNull();
        assertThat(response.investorCount()).isNull();
    }

    /** Khoản đã đóng/hủy: committedAmount đã về 0 sau khi hoàn tiền, raisedAmount giữ số đã gọi được để vẽ tiến độ. */
    @Test
    void closedListingCarriesRaisedAmountAndInvestorCount() {
        MarketListing listing = MarketListing.builder()
                .id(6L).loanId(78L).applicationNumber("LA-0123456789ABCDEF0124")
                .productCode("SP-01").purpose("Du lịch").region("Không công bố").creditGrade("C").creditScore(60)
                .targetAmount(new BigDecimal("10000000.00")).committedAmount(new BigDecimal("0.00"))
                .raisedAmount(new BigDecimal("7000000.00"))
                .annualInterestRate(new BigDecimal("16.0000")).termMonths(6).repaymentMethod("ANNUITY")
                .noteDenomination(new BigDecimal("1000000.00")).minInvestmentAmount(new BigDecimal("1000000.00"))
                .status(ListingStatus.CLOSED).fundingRound(1)
                .fundingOpenedAt(NOW).fundingClosesAt(NOW.plusSeconds(86_400))
                .build();

        MarketListingResponse response = new InvestmentMapper().toListingResponse(listing, "Nguyễn Văn An", 3);

        assertThat(response.committedAmount()).isEqualTo("0.00");
        assertThat(response.raisedAmount()).isEqualTo("7000000.00");
        assertThat(response.investorCount()).isEqualTo(3);
        assertThat(response.borrowerFullName()).isEqualTo("Nguyễn Văn An");
    }

    /** Người đang nắm giữ là investorId hiện tại của Note, đã đổi theo chuyển nhượng trên Chợ. */
    @Test
    void noteResponseCarriesCurrentHolder() {
        InvestmentNote note = new InvestmentNote();
        note.setNoteNumber("NOTE-77-9-0001");
        note.setLoanId(77L);
        note.setInvestorId("buyer-42");
        note.setPrincipalAmount(new BigDecimal("1000000.00"));
        note.setOutstandingPrincipal(new BigDecimal("1000000.00"));
        note.setPrincipalRepaid(new BigDecimal("0.00"));
        note.setInterestReceived(new BigDecimal("0.00"));
        note.setAnnualInterestRate(new BigDecimal("15.0000"));
        note.setTermMonths(12);
        note.setStatus(NoteStatus.ACTIVE);
        note.setIssuedAt(NOW);

        NoteResponse response = new InvestmentMapper().toNoteResponse(note);

        assertThat(response.holderId()).isEqualTo("buyer-42");
    }
}
