package com.finora.investment.repository;

import com.finora.investment.domain.MatchResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {

    List<MatchResult> findByListingId(Long listingId);

    List<MatchResult> findByOrderId(Long orderId);
}
