package com.finora.payment.repository.topup;

import com.finora.payment.domain.topup.PaymentTopUp;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTopUpRepository extends JpaRepository<PaymentTopUp, Long> {

    Optional<PaymentTopUp> findByTopUpId(UUID topUpId);

    Optional<PaymentTopUp> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTopUp> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select topUp from PaymentTopUp topUp where topUp.topUpId = :topUpId")
    Optional<PaymentTopUp> findByTopUpIdForUpdate(@Param("topUpId") UUID topUpId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select topUp from PaymentTopUp topUp
            where topUp.provider = :provider and topUp.providerOrderId = :providerOrderId
            """)
    Optional<PaymentTopUp> findByProviderOrderForUpdate(
            @Param("provider") String provider,
            @Param("providerOrderId") String providerOrderId);
}
