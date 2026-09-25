package com.finora.payment.service.wallet;

import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.domain.wallet.WalletStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record WalletView(
        UUID walletId,
        WalletOwnerType ownerType,
        String ownerId,
        String currency,
        BigDecimal availableBalance,
        BigDecimal heldBalance,
        WalletStatus status
) {
    public static WalletView from(PaymentWallet wallet) {
        return new WalletView(
                wallet.getWalletId(), wallet.getOwnerType(), wallet.getOwnerId(), wallet.getCurrency(),
                wallet.getAvailableBalance(), wallet.getHeldBalance(), wallet.getStatus());
    }
}
