package com.finora.payment.repository.ledger;

import com.finora.payment.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO payment_ledger_transactions (
                transaction_id, idempotency_key, request_hash, transaction_type,
                reference_type, reference_id, currency, total_amount,
                status, created_at, updated_at
            ) VALUES (
                :transactionId, :idempotencyKey, :requestHash, :transactionType,
                :referenceType, :referenceId, :currency, :totalAmount,
                'PROCESSING', :now, :now
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("transactionId") UUID transactionId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("transactionType") String transactionType,
            @Param("referenceType") String referenceType,
            @Param("referenceId") String referenceId,
            @Param("currency") String currency,
            @Param("totalAmount") BigDecimal totalAmount,
            @Param("now") Instant now
    );

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select transaction from LedgerTransaction transaction
            where transaction.status = com.finora.payment.domain.ledger.LedgerTransactionStatus.POSTED
              and exists (
                  select entry.id from LedgerEntry entry
                  where entry.transaction = transaction and entry.wallet.walletId = :walletId
              )
            order by transaction.postedAt desc, transaction.id desc
            """)
    List<LedgerTransaction> findWalletStatement(
            @Param("walletId") UUID walletId,
            Pageable pageable);
}
