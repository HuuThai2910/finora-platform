package com.finora.investment.service;

import com.finora.investment.domain.LoanListing;
import com.finora.investment.domain.MatchResult;
import com.finora.investment.repository.LoanListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Quản lý tiến độ gọi vốn — cập nhật fundedAmount, investorCount.
 * Gọi sau khi matching engine khớp lệnh.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundingService {

    private final LoanListingRepository listingRepo;
    private final NoteService noteService;

    /**
     * Xử lý sau khi matching: tạo Notes + cập nhật funding progress.
     */
    @Transactional
    public void processMatches(List<MatchResult> matches) {
        for (MatchResult match : matches) {
            // Tạo Note cho investor
            noteService.createFromMatch(match);

            // Cập nhật funding progress
            LoanListing listing = match.getListing();
            listing.addFunding(match.getMatchedAmount());
            listingRepo.save(listing);

            if (listing.isFullyFunded()) {
                log.info("Listing {} đã gọi vốn 100% — sẵn sàng giải ngân",
                        listing.getId());
                // TODO: gửi event FUNDING_COMPLETE → trigger giải ngân
            }
        }
    }
}
