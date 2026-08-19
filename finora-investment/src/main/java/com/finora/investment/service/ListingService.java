package com.finora.investment.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.investment.domain.LoanListing;
import com.finora.investment.domain.ListingStatus;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.repository.LoanListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final LoanListingRepository listingRepo;

    @Transactional
    public LoanListing create(CreateListingRequest req) {
        var listing = LoanListing.builder()
                .loanApplicationId(req.loanApplicationId())
                .borrowerId(req.borrowerId())
                .amount(req.amount())
                .remainingAmount(req.amount())
                .termMonths(req.termMonths())
                .grade(req.grade().toUpperCase())
                .interestRate(req.interestRate())
                .build();
        return listingRepo.save(listing);
    }

    @Transactional(readOnly = true)
    public Page<LoanListing> findOpen(String grade, BigDecimal minRate, BigDecimal maxRate, Pageable pageable) {
        return listingRepo.findOpenListings(grade, minRate, maxRate, pageable);
    }

    @Transactional(readOnly = true)
    public LoanListing findById(Long id) {
        return listingRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", "id", id));
    }

    @Transactional
    public void cancel(Long id) {
        var listing = findById(id);
        if (listing.getStatus() != ListingStatus.OPEN) {
            throw new BusinessException("Chỉ huỷ được listing đang OPEN");
        }
        listing.setStatus(ListingStatus.CANCELLED);
        listingRepo.save(listing);
    }
}
