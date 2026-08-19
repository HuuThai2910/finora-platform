package com.finora.investment.service;

import com.finora.investment.domain.*;
import com.finora.investment.dto.request.AutoInvestRequest;
import com.finora.investment.repository.AutoInvestConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoInvestServiceTest {

    @Mock AutoInvestConfigRepository configRepo;
    @Mock OrderService orderService;
    @Mock MatchingEngineService matchingEngine;
    @Mock FundingService fundingService;
    @InjectMocks AutoInvestService autoInvestService;

    @Test
    void createOrUpdate_newConfig() {
        var req = new AutoInvestRequest(
                10L, "A,B", null, null, null, null,
                new BigDecimal("2000000"), new BigDecimal("20000000"));

        when(configRepo.findByInvestorId(10L)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> {
            AutoInvestConfig c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        var result = autoInvestService.createOrUpdate(req);

        assertThat(result.getInvestorId()).isEqualTo(10L);
        assertThat(result.getAmountPerNote()).isEqualByComparingTo("2000000");
        assertThat(result.getRemainingBudget()).isEqualByComparingTo("20000000");
        assertThat(result.getIsActive()).isTrue();
    }

    @Test
    void onNewListing_matchesConfigAndDeductsBudget() {
        var listing = LoanListing.builder()
                .id(1L).amount(new BigDecimal("10000000"))
                .remainingAmount(new BigDecimal("10000000"))
                .termMonths(12).grade("B").interestRate(new BigDecimal("12.50"))
                .status(ListingStatus.OPEN).build();

        var config = AutoInvestConfig.builder()
                .id(1L).investorId(20L).gradeFilter("A,B")
                .amountPerNote(new BigDecimal("2000000"))
                .totalBudget(new BigDecimal("10000000"))
                .remainingBudget(new BigDecimal("10000000"))
                .isActive(true).build();

        var order = InvestmentOrder.builder()
                .id(1L).investorId(20L)
                .amount(new BigDecimal("2000000"))
                .remainingAmount(BigDecimal.ZERO)
                .status(OrderStatus.MATCHED).build();

        var match = MatchResult.builder()
                .id(1L).listing(listing).order(order)
                .matchedAmount(new BigDecimal("2000000")).build();

        when(configRepo.findByIsActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(config));
        when(orderService.create(any())).thenReturn(order);
        when(matchingEngine.matchOrder(order)).thenReturn(List.of(match));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoInvestService.onNewListing(listing);

        verify(fundingService).processMatches(List.of(match));
        assertThat(config.getRemainingBudget()).isEqualByComparingTo("8000000");
    }

    @Test
    void onNewListing_skipsNonMatchingGrade() {
        var listing = LoanListing.builder()
                .id(2L).amount(new BigDecimal("5000000"))
                .remainingAmount(new BigDecimal("5000000"))
                .termMonths(6).grade("D").interestRate(new BigDecimal("18.00"))
                .status(ListingStatus.OPEN).build();

        var config = AutoInvestConfig.builder()
                .id(1L).investorId(20L).gradeFilter("A,B")
                .amountPerNote(new BigDecimal("2000000"))
                .totalBudget(new BigDecimal("10000000"))
                .remainingBudget(new BigDecimal("10000000"))
                .isActive(true).build();

        when(configRepo.findByIsActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(config));

        autoInvestService.onNewListing(listing);

        verify(orderService, never()).create(any());
    }

    @Test
    void onNewListing_deactivatesWhenBudgetLow() {
        var listing = LoanListing.builder()
                .id(3L).amount(new BigDecimal("5000000"))
                .remainingAmount(new BigDecimal("5000000"))
                .termMonths(12).grade("A").interestRate(new BigDecimal("10.00"))
                .status(ListingStatus.OPEN).build();

        var config = AutoInvestConfig.builder()
                .id(1L).investorId(20L)
                .amountPerNote(new BigDecimal("3000000"))
                .totalBudget(new BigDecimal("3000000"))
                .remainingBudget(new BigDecimal("3000000"))
                .isActive(true).build();

        var order = InvestmentOrder.builder()
                .id(2L).investorId(20L)
                .amount(new BigDecimal("3000000"))
                .remainingAmount(BigDecimal.ZERO)
                .status(OrderStatus.MATCHED).build();

        var match = MatchResult.builder()
                .id(2L).listing(listing).order(order)
                .matchedAmount(new BigDecimal("3000000")).build();

        when(configRepo.findByIsActiveTrueOrderByCreatedAtAsc()).thenReturn(List.of(config));
        when(orderService.create(any())).thenReturn(order);
        when(matchingEngine.matchOrder(order)).thenReturn(List.of(match));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoInvestService.onNewListing(listing);

        assertThat(config.getIsActive()).isFalse();  // Budget exhausted
        assertThat(config.getRemainingBudget()).isEqualByComparingTo("0");
    }
}
