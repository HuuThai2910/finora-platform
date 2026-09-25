package com.finora.payment.repository.ledger;

import com.finora.payment.domain.ledger.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByTransaction_Id(Long transactionId);
}
