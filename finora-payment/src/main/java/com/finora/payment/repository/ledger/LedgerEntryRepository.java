package com.finora.payment.repository.ledger;

import com.finora.payment.domain.ledger.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByTransaction_Id(Long transactionId);

    @Query("""
            select entry from LedgerEntry entry
            join fetch entry.transaction transaction
            where entry.wallet.walletId = :walletId
              and transaction.status = com.finora.payment.domain.ledger.LedgerTransactionStatus.POSTED
              and (
                transaction.transactionType not in (
                  com.finora.payment.domain.ledger.LedgerTransactionType.HOLD,
                  com.finora.payment.domain.ledger.LedgerTransactionType.RELEASE
                )
                or entry.balanceBucket = com.finora.payment.domain.ledger.LedgerBalanceBucket.AVAILABLE
              )
            order by transaction.postedAt desc, transaction.id desc, entry.entrySequence
            """)
    List<LedgerEntry> findWalletEntries(@Param("walletId") UUID walletId, Pageable pageable);
}
