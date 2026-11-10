package com.finora.investment.service.impl;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.order.entity.InvestmentCommitment;
import com.finora.investment.domain.order.entity.InvestmentOrder;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Đóng/hủy listing: hoàn phần vốn ACTIVE, trừ tiến độ và chốt số đã gọi được trước khi trừ. */
class ListingCommitmentCancellerImplTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final InvestmentCommitmentRepository investmentCommitmentRepository =
            mock(InvestmentCommitmentRepository.class);
    private final InvestmentOrderRepository investmentOrderRepository = mock(InvestmentOrderRepository.class);
    private final ListingCommitmentCancellerImpl canceller =
            new ListingCommitmentCancellerImpl(investmentCommitmentRepository, investmentOrderRepository);

    @Test
    void raisedAmountKeepsWhatWasCommittedBeforeRefund() {
        MarketListing listing = listing(new BigDecimal("7000000.00"));
        when(investmentCommitmentRepository.findByListingIdAndStatus(5L, CommitmentStatus.ACTIVE))
                .thenReturn(List.of(commitment(11L, 101L, "inv-1", "4000000.00"),
                        commitment(12L, 102L, "inv-2", "3000000.00")));
        when(investmentOrderRepository.findAllById(anyList())).thenReturn(List.of(order(101L), order(102L)));

        canceller.cancelActive(listing, "LISTING_EXPIRED", "hết hạn", "SYSTEM", NOW);

        assertThat(listing.getCommittedAmount()).isEqualByComparingTo("0");
        assertThat(listing.getRaisedAmount()).isEqualByComparingTo("7000000.00");
    }

    @Test
    void raisedAmountIsSetWhenNothingIsLeftAndNotOverwrittenByRepeatedCancel() {
        MarketListing listing = listing(new BigDecimal("0.00"));
        when(investmentCommitmentRepository.findByListingIdAndStatus(5L, CommitmentStatus.ACTIVE)).thenReturn(List.of());

        canceller.cancelActive(listing, "LISTING_EXPIRED", "hết hạn", "SYSTEM", NOW);
        assertThat(listing.getRaisedAmount()).isEqualByComparingTo("0");

        listing.setRaisedAmount(new BigDecimal("5000000.00"));
        canceller.cancelActive(listing, "LOAN_FUNDING_CANCELLED", "hủy", "SYSTEM", NOW);
        assertThat(listing.getRaisedAmount()).isEqualByComparingTo("5000000.00");
    }

    private static MarketListing listing(BigDecimal committed) {
        return MarketListing.builder().id(5L).loanId(77L).status(ListingStatus.OPEN)
                .targetAmount(new BigDecimal("10000000.00")).committedAmount(committed).build();
    }

    private static InvestmentCommitment commitment(Long id, Long orderId, String investorId, String amount) {
        InvestmentCommitment commitment = new InvestmentCommitment();
        commitment.setId(id);
        commitment.setOrderId(orderId);
        commitment.setListingId(5L);
        commitment.setInvestorId(investorId);
        commitment.setAmount(new BigDecimal(amount));
        commitment.setStatus(CommitmentStatus.ACTIVE);
        return commitment;
    }

    private static InvestmentOrder order(Long id) {
        InvestmentOrder order = new InvestmentOrder();
        order.setId(id);
        order.setStatus(OrderStatus.COMMITTED);
        return order;
    }
}
