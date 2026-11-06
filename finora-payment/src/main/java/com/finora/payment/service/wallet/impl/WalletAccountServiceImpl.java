package com.finora.payment.service.wallet.impl;

import com.finora.payment.domain.wallet.entity.PaymentWallet;
import com.finora.payment.domain.wallet.enums.WalletOwnerType;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Mở wallet idempotent theo owner/currency; chưa phải thao tác nạp tiền. */
@Service
@RequiredArgsConstructor
public class WalletAccountServiceImpl implements WalletAccountService {

    private final PaymentWalletRepository paymentWalletRepository;
    private final Clock clock;

    @Override
    @Transactional
    public WalletView open(WalletOwnerType ownerType, String ownerId, String currency) {
        Instant now = clock.instant();
        PaymentWallet candidate = PaymentWallet.open(UUID.randomUUID(), ownerType, ownerId, currency, now);
        paymentWalletRepository.insertIfAbsent(
                candidate.getWalletId(), candidate.getOwnerType().name(), candidate.getOwnerId(),
                candidate.getCurrency(), now);
        PaymentWallet wallet = paymentWalletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                        candidate.getOwnerType(), candidate.getOwnerId(), candidate.getCurrency())
                .orElseThrow(() -> new IllegalStateException("Không đọc được wallet sau thao tác idempotent insert"));
        return WalletView.from(wallet);
    }
}
