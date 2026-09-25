package com.finora.payment.repository.ledger;

import com.finora.payment.domain.ledger.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
