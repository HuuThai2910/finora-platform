package com.finora.investment.repository;

import com.finora.investment.domain.LoanListing;
import com.finora.investment.domain.ListingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LoanListingRepository extends JpaRepository<LoanListing, Long> {

    Page<LoanListing> findByStatus(ListingStatus status, Pageable pageable);

    @Query("""
        SELECT l FROM LoanListing l
        WHERE l.status = 'OPEN'
          AND (:grade IS NULL OR l.grade = :grade)
          AND (:minRate IS NULL OR l.interestRate >= :minRate)
          AND (:maxRate IS NULL OR l.interestRate <= :maxRate)
        ORDER BY l.createdAt ASC
        """)
    Page<LoanListing> findOpenListings(
            @Param("grade") String grade,
            @Param("minRate") BigDecimal minRate,
            @Param("maxRate") BigDecimal maxRate,
            Pageable pageable);

    /** Listing OPEN, sắp theo thời gian (price-time priority). */
    List<LoanListing> findByStatusOrderByCreatedAtAsc(ListingStatus status);
}
