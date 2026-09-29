package com.finora.payment.dto.response;

import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.util.UUID;

public record WalletBalanceResponse(
        UUID walletId,
        String ownerType,
        String currency,
        BigDecimal available,
        BigDecimal held,
        String status
) {
    public static WalletBalanceResponse from(WalletView wallet) {
        return new WalletBalanceResponse(
                wallet.walletId(), wallet.ownerType().name(), wallet.currency(),
                wallet.availableBalance(), wallet.heldBalance(), wallet.status().name());
    }
}
