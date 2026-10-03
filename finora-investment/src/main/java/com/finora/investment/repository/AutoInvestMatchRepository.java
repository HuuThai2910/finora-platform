package com.finora.investment.repository;

import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoInvestMatchRepository extends JpaRepository<AutoInvestMatch, Long> {

    List<AutoInvestMatch> findByInvestorIdOrderByCreatedAtDescIdDesc(String investorId, Pageable pageable);

    List<AutoInvestMatch> findByListingIdOrderByIdAsc(Long listingId);

    boolean existsByInvestorIdAndListingId(String investorId, Long listingId);
}
