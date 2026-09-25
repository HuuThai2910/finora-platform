package com.finora.payment.repository.wallet;

import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentWalletRepository extends JpaRepository<PaymentWallet, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO payment_wallets (
                wallet_id, owner_type, owner_id, currency, available_balance, held_balance,
                status, version, created_at, updated_at
            ) VALUES (:walletId, :ownerType, :ownerId, :currency, 0, 0, 'ACTIVE', 0, :now, :now)
            ON CONFLICT (owner_type, owner_id, currency) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("walletId") UUID walletId,
            @Param("ownerType") String ownerType,
            @Param("ownerId") String ownerId,
            @Param("currency") String currency,
            @Param("now") Instant now
    );

    Optional<PaymentWallet> findByOwnerTypeAndOwnerIdAndCurrency(
            WalletOwnerType ownerType, String ownerId, String currency);

    Optional<PaymentWallet> findByWalletId(UUID walletId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from PaymentWallet wallet where wallet.walletId in :walletIds order by wallet.id")
    List<PaymentWallet> findAllByWalletIdInForUpdate(@Param("walletIds") Collection<UUID> walletIds);
}
