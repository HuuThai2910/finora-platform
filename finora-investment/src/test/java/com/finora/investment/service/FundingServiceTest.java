package com.finora.investment.service;

import com.finora.investment.domain.*;
import com.finora.investment.repository.LoanListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FundingServiceTest {

    @Mock LoanListingRepository listingRepo;
    @Mock NoteService noteService;
    @InjectMocks FundingService fundingService;

    @Test
    void processMatches_createsNotesAndUpdatesFunding() {
        var listing = LoanListing.builder()
                .id(1L).amount(new BigDecimal("10000000"))
                .remainingAmount(new BigDecimal("5000000"))
                .fundedAmount(BigDecimal.ZERO).investorCount(0)
                .grade("B").interestRate(new BigDecimal("12.50"))
                .status(ListingStatus.OPEN).build();

        var order = InvestmentOrder.builder()
                .id(1L).investorId(20L)
                .amount(new BigDecimal("5000000"))
                .remainingAmount(BigDecimal.ZERO)
                .status(OrderStatus.MATCHED).build();

        var match = MatchResult.builder()
                .id(1L).listing(listing).order(order)
                .matchedAmount(new BigDecimal("5000000")).build();

        when(noteService.createFromMatch(any())).thenReturn(InvestmentNote.builder().id(1L).build());
        when(listingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fundingService.processMatches(List.of(match));

        verify(noteService).createFromMatch(match);
        assertThat(listing.getFundedAmount()).isEqualByComparingTo("5000000");
        assertThat(listing.getInvestorCount()).isEqualTo(1);
    }

    @Test
    void processMatches_detectsFullyFunded() {
        var listing = LoanListing.builder()
                .id(2L).amount(new BigDecimal("5000000"))
                .remainingAmount(BigDecimal.ZERO)
                .fundedAmount(BigDecimal.ZERO).investorCount(0)
                .grade("A").interestRate(new BigDecimal("10.00"))
                .status(ListingStatus.FUNDED).build();

        var order = InvestmentOrder.builder()
                .id(2L).investorId(30L)
                .amount(new BigDecimal("5000000"))
                .remainingAmount(BigDecimal.ZERO)
                .status(OrderStatus.MATCHED).build();

        var match = MatchResult.builder()
                .id(2L).listing(listing).order(order)
                .matchedAmount(new BigDecimal("5000000")).build();

        when(noteService.createFromMatch(any())).thenReturn(InvestmentNote.builder().id(2L).build());
        when(listingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fundingService.processMatches(List.of(match));

        assertThat(listing.isFullyFunded()).isTrue();
        assertThat(listing.getFundedPercentage()).isEqualByComparingTo("100.00");
    }
}
