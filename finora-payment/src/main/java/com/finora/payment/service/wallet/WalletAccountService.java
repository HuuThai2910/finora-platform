package com.finora.payment.service.wallet;

import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mở wallet idempotent theo owner/currency; chưa phải thao tác nạp tiền. */
@Service
@RequiredArgsConstructor
public class WalletAccountService {

    private final PaymentWalletRepository repository;
    private final Clock clock;

    @Transactional
    public WalletView open(WalletOwnerType ownerType, String ownerId, String currency) {
        Instant now = clock.instant();
        PaymentWallet candidate = PaymentWallet.open(UUID.randomUUID(), ownerType, ownerId, currency, now);
        repository.insertIfAbsent(
                candidate.getWalletId(), candidate.getOwnerType().name(), candidate.getOwnerId(),
                candidate.getCurrency(), now);
        PaymentWallet wallet = repository.findByOwnerTypeAndOwnerIdAndCurrency(
                        candidate.getOwnerType(), candidate.getOwnerId(), candidate.getCurrency())
                .orElseThrow(() -> new IllegalStateException("Không đọc được wallet sau thao tác idempotent insert"));
        return WalletView.from(wallet);
    }
}
