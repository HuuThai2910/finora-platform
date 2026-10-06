package com.finora.payment.repository.repayment;

import com.finora.payment.domain.repayment.EarlySettlementQuote;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EarlySettlementQuoteRepository extends JpaRepository<EarlySettlementQuote, Long> {
    Optional<EarlySettlementQuote> findByQuoteId(UUID quoteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quote from EarlySettlementQuote quote where quote.quoteId = :quoteId")
    Optional<EarlySettlementQuote> findByQuoteIdForUpdate(@Param("quoteId") UUID quoteId);
}
