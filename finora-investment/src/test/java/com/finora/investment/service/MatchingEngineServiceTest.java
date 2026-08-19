package com.finora.investment.service;

import com.finora.investment.domain.*;
import com.finora.investment.repository.*;
import org.junit.jupiter.api.BeforeEach;
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
class MatchingEngineServiceTest {

    @Mock LoanListingRepository listingRepo;
    @Mock InvestmentOrderRepository orderRepo;
    @Mock MatchResultRepository matchRepo;
    @InjectMocks MatchingEngineService engine;

    private LoanListing listing;
    private InvestmentOrder order;

    @BeforeEach
    void setUp() {
        listing = LoanListing.builder()
                .id(1L)
                .loanApplicationId(100L)
                .borrowerId(10L)
                .amount(new BigDecimal("10000000"))
                .remainingAmount(new BigDecimal("10000000"))
                .termMonths(12)
                .grade("B")
                .interestRate(new BigDecimal("12.50"))
                .status(ListingStatus.OPEN)
                .build();

        order = InvestmentOrder.builder()
                .id(1L)
                .investorId(20L)
                .amount(new BigDecimal("5000000"))
                .remainingAmount(new BigDecimal("5000000"))
                .status(OrderStatus.PENDING)
                .build();
    }

    @Test
    void matchListing_fullMatchWithOneOrder() {
        // Order 5M, Listing 10M → partial fill, order fully matched
        when(orderRepo.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(order));
        when(matchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(listingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<MatchResult> results = engine.matchListing(listing);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMatchedAmount()).isEqualByComparingTo("5000000");
        assertThat(listing.getRemainingAmount()).isEqualByComparingTo("5000000");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.MATCHED);
    }

    @Test
    void matchListing_partialFillMultipleOrders() {
        var order2 = InvestmentOrder.builder()
                .id(2L)
                .investorId(30L)
                .amount(new BigDecimal("8000000"))
                .remainingAmount(new BigDecimal("8000000"))
                .status(OrderStatus.PENDING)
                .build();

        when(orderRepo.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(order, order2));
        when(matchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(listingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<MatchResult> results = engine.matchListing(listing);

        assertThat(results).hasSize(2);
        // Order1: 5M khớp, Order2: 5M khớp (còn 3M remaining)
        assertThat(results.get(0).getMatchedAmount()).isEqualByComparingTo("5000000");
        assertThat(results.get(1).getMatchedAmount()).isEqualByComparingTo("5000000");
        assertThat(listing.getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.FUNDED);
        assertThat(order2.getStatus()).isEqualTo(OrderStatus.PARTIALLY_MATCHED);
    }

    @Test
    void matchListing_gradeFilterSkipsNonMatching() {
        order.setGradeFilter("A,C");  // Listing is grade B → should skip

        when(orderRepo.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(order));

        List<MatchResult> results = engine.matchListing(listing);

        assertThat(results).isEmpty();
        verify(matchRepo, never()).save(any());
    }

    @Test
    void matchOrder_matchesOpenListings() {
        when(listingRepo.findByStatusOrderByCreatedAtAsc(ListingStatus.OPEN)).thenReturn(List.of(listing));
        when(matchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(listingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<MatchResult> results = engine.matchOrder(order);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMatchedAmount()).isEqualByComparingTo("5000000");
    }

    @Test
    void matchOrder_rateFilterWorks() {
        order.setMinRate(new BigDecimal("15.00"));  // Listing rate 12.50 < min 15 → skip

        when(listingRepo.findByStatusOrderByCreatedAtAsc(ListingStatus.OPEN)).thenReturn(List.of(listing));

        List<MatchResult> results = engine.matchOrder(order);

        assertThat(results).isEmpty();
    }

    @Test
    void matchListing_cancelledListingSkipped() {
        listing.setStatus(ListingStatus.CANCELLED);

        List<MatchResult> results = engine.matchListing(listing);

        assertThat(results).isEmpty();
        verifyNoInteractions(orderRepo);
    }
}
