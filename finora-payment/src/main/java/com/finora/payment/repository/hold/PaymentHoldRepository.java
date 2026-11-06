package com.finora.payment.repository.hold;

import com.finora.payment.domain.hold.entity.PaymentHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentHoldRepository extends JpaRepository<PaymentHold, Long> {

    Optional<PaymentHold> findByOrderReference(String orderReference);

    Optional<PaymentHold> findByHoldReference(String holdReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hold from PaymentHold hold where hold.holdReference = :reference")
    Optional<PaymentHold> findByHoldReferenceForUpdate(@Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select hold from PaymentHold hold
            join fetch hold.wallet
            where hold.holdReference in :references
            order by hold.id
            """)
    List<PaymentHold> findAllByHoldReferenceInForUpdate(@Param("references") Collection<String> references);
}
