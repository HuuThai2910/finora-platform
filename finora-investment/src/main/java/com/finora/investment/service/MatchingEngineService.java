package com.finora.investment.service;

import com.finora.investment.domain.*;
import com.finora.investment.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Core matching engine — price-time priority.
 *
 * Khi có listing mới: quét tất cả order đang pending/partially_matched,
 * khớp theo FIFO (order cũ nhất trước), partial fill.
 *
 * Khi có order mới: quét tất cả listing đang OPEN,
 * khớp theo FIFO (listing cũ nhất trước), partial fill.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineService {

    private final LoanListingRepository listingRepo;
    private final InvestmentOrderRepository orderRepo;
    private final MatchResultRepository matchRepo;

    /**
     * Khớp 1 listing mới với các order đang chờ.
     * Gọi sau khi tạo listing.
     */
    @Transactional
    public List<MatchResult> matchListing(LoanListing listing) {
        if (!listing.isOpen()) return List.of();

        List<OrderStatus> pendingStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_MATCHED);
        List<InvestmentOrder> pendingOrders = orderRepo.findByStatusInOrderByCreatedAtAsc(pendingStatuses);

        List<MatchResult> results = new ArrayList<>();

        for (InvestmentOrder order : pendingOrders) {
            if (listing.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0) break;
            if (!order.matchesListing(listing)) continue;

            BigDecimal matchAmount = listing.getRemainingAmount().min(order.getRemainingAmount());

            var match = MatchResult.builder()
                    .listing(listing)
                    .order(order)
                    .matchedAmount(matchAmount)
                    .build();

            listing.deductAmount(matchAmount);
            order.deductAmount(matchAmount);

            matchRepo.save(match);
            listingRepo.save(listing);
            orderRepo.save(order);
            results.add(match);

            log.info("Khớp lệnh: listing={} order={} amount={}",
                    listing.getId(), order.getId(), matchAmount);
        }

        return results;
    }

    /**
     * Khớp 1 order mới với các listing đang mở.
     * Gọi sau khi tạo order.
     */
    @Transactional
    public List<MatchResult> matchOrder(InvestmentOrder order) {
        if (!order.isPending()) return List.of();

        List<LoanListing> openListings = listingRepo.findByStatusOrderByCreatedAtAsc(ListingStatus.OPEN);

        List<MatchResult> results = new ArrayList<>();

        for (LoanListing listing : openListings) {
            if (order.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0) break;
            if (!order.matchesListing(listing)) continue;

            BigDecimal matchAmount = listing.getRemainingAmount().min(order.getRemainingAmount());

            var match = MatchResult.builder()
                    .listing(listing)
                    .order(order)
                    .matchedAmount(matchAmount)
                    .build();

            listing.deductAmount(matchAmount);
            order.deductAmount(matchAmount);

            matchRepo.save(match);
            listingRepo.save(listing);
            orderRepo.save(order);
            results.add(match);

            log.info("Khớp lệnh: listing={} order={} amount={}",
                    listing.getId(), order.getId(), matchAmount);
        }

        return results;
    }
}
