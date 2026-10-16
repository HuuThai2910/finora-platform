package com.finora.investment.repository;

import com.finora.investment.domain.restructure.InvestorRescheduleConsent;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestorRescheduleConsentRepository extends JpaRepository<InvestorRescheduleConsent, Long> {
    boolean existsByRequestId(UUID requestId);
    Page<InvestorRescheduleConsent> findByInvestorIdOrderByCreatedAtDesc(String investorId, Pageable pageable);
    Optional<InvestorRescheduleConsent> findByRequestIdAndInvestorId(UUID requestId, String investorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvestorRescheduleConsent c where c.requestId = :requestId order by c.id")
    List<InvestorRescheduleConsent> findAllByRequestIdForUpdate(@Param("requestId") UUID requestId);
}
