package com.finora.payment.repository.repayment;

import com.finora.payment.domain.repayment.PartialPrepaymentQuote;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartialPrepaymentQuoteRepository extends JpaRepository<PartialPrepaymentQuote, Long> {
    Optional<PartialPrepaymentQuote> findByQuoteId(UUID quoteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quote from PartialPrepaymentQuote quote where quote.quoteId = :quoteId")
    Optional<PartialPrepaymentQuote> findByQuoteIdForUpdate(@Param("quoteId") UUID quoteId);
}
