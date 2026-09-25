package com.finora.payment.service.ledger;

import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerEntry;
import com.finora.payment.domain.ledger.LedgerTransaction;
import com.finora.payment.domain.ledger.LedgerTransactionStatus;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletStatus;
import com.finora.payment.repository.ledger.LedgerEntryRepository;
import com.finora.payment.repository.ledger.LedgerTransactionRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Post transaction cân bằng; balance, transaction và entries commit hoặc rollback cùng nhau. */
@Service
@RequiredArgsConstructor
public class LedgerPostingService {

    private final PaymentWalletRepository walletRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final Clock clock;

    @Transactional
    public LedgerPostingResult post(LedgerPostingCommand command) {
        PostingAmounts amounts = validateBalanced(command.entries());
        String requestHash = requestHash(command);
        Instant now = clock.instant();
        UUID transactionId = UUID.randomUUID();

        int inserted = transactionRepository.insertIfAbsent(
                transactionId, command.idempotencyKey(), requestHash, command.transactionType().name(),
                command.referenceType(), command.referenceId(), command.currency(), amounts.debits(), now);
        LedgerTransaction transaction = transactionRepository.findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Không đọc được ledger transaction sau idempotent insert"));

        if (inserted == 0) {
            if (!transaction.getRequestHash().equals(requestHash)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "PAYMENT_IDEMPOTENCY_CONFLICT",
                        "Idempotency key đã được dùng với nội dung giao dịch khác");
            }
            if (transaction.getStatus() != LedgerTransactionStatus.POSTED) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "PAYMENT_TRANSACTION_INCOMPLETE",
                        "Giao dịch cùng idempotency key chưa hoàn tất; cần reconciliation");
            }
            return result(transaction, true);
        }

        Map<UUID, PaymentWallet> wallets = lockWallets(command);
        applyWalletDeltas(command, wallets, now);

        List<LedgerEntry> entries = new ArrayList<>(command.entries().size());
        for (int index = 0; index < command.entries().size(); index++) {
            LedgerPostingEntryCommand entry = command.entries().get(index);
            PaymentWallet wallet = entry.walletId() == null ? null : wallets.get(entry.walletId());
            entries.add(LedgerEntry.create(
                    transaction, index + 1, wallet, entry.accountCode(), entry.balanceBucket(),
                    entry.direction(), entry.amount(), now));
        }
        entryRepository.saveAll(entries);
        transaction.markPosted(now);
        transactionRepository.saveAndFlush(transaction);
        return result(transaction, false);
    }

    private Map<UUID, PaymentWallet> lockWallets(LedgerPostingCommand command) {
        Set<UUID> walletIds = new LinkedHashSet<>();
        command.entries().stream()
                .map(LedgerPostingEntryCommand::walletId)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(walletIds::add);
        List<PaymentWallet> locked = walletRepository.findAllByWalletIdInForUpdate(walletIds);
        if (locked.size() != walletIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_WALLET_NOT_FOUND", "Không tìm thấy wallet");
        }
        Map<UUID, PaymentWallet> wallets = new HashMap<>();
        for (PaymentWallet wallet : locked) {
            if (!wallet.getCurrency().equals(command.currency())) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "PAYMENT_CURRENCY_MISMATCH",
                        "Currency của wallet không khớp transaction");
            }
            wallets.put(wallet.getWalletId(), wallet);
        }
        return wallets;
    }

    private void applyWalletDeltas(
            LedgerPostingCommand command,
            Map<UUID, PaymentWallet> wallets,
            Instant now
    ) {
        Map<UUID, BigDecimal> available = new HashMap<>();
        Map<UUID, BigDecimal> held = new HashMap<>();
        for (LedgerPostingEntryCommand entry : command.entries()) {
            if (entry.walletId() == null) {
                continue;
            }
            BigDecimal delta = entry.direction() == LedgerDirection.CREDIT
                    ? entry.amount()
                    : entry.amount().negate();
            Map<UUID, BigDecimal> target = entry.balanceBucket() == LedgerBalanceBucket.AVAILABLE
                    ? available
                    : held;
            target.merge(entry.walletId(), delta, BigDecimal::add);
        }

        for (PaymentWallet wallet : wallets.values()) {
            if (wallet.getStatus() != WalletStatus.ACTIVE) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_WALLET_NOT_ACTIVE", "Wallet không hoạt động");
            }
            BigDecimal availableDelta = available.getOrDefault(wallet.getWalletId(), BigDecimal.ZERO);
            BigDecimal heldDelta = held.getOrDefault(wallet.getWalletId(), BigDecimal.ZERO);
            if (wallet.getAvailableBalance().add(availableDelta).signum() < 0
                    || wallet.getHeldBalance().add(heldDelta).signum() < 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "PAYMENT_INSUFFICIENT_BALANCE",
                        "Số dư wallet không đủ cho giao dịch");
            }
            wallet.apply(availableDelta, heldDelta, now);
        }
    }

    private static PostingAmounts validateBalanced(List<LedgerPostingEntryCommand> entries) {
        BigDecimal debits = BigDecimal.ZERO.setScale(2);
        BigDecimal credits = BigDecimal.ZERO.setScale(2);
        for (LedgerPostingEntryCommand entry : entries) {
            if (entry.direction() == LedgerDirection.DEBIT) {
                debits = debits.add(entry.amount());
            } else {
                credits = credits.add(entry.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PAYMENT_LEDGER_UNBALANCED",
                    "Tổng debit và credit phải bằng nhau");
        }
        return new PostingAmounts(debits, credits);
    }

    private static String requestHash(LedgerPostingCommand command) {
        StringBuilder canonical = new StringBuilder()
                .append(command.transactionType()).append('|')
                .append(command.referenceType()).append('|')
                .append(command.referenceId()).append('|')
                .append(command.currency());
        for (LedgerPostingEntryCommand entry : command.entries()) {
            canonical.append('\n')
                    .append(entry.accountCode()).append('|')
                    .append(entry.balanceBucket()).append('|')
                    .append(entry.direction()).append('|')
                    .append(entry.amount().toPlainString());
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", impossible);
        }
    }

    private static LedgerPostingResult result(LedgerTransaction transaction, boolean replayed) {
        return new LedgerPostingResult(
                transaction.getTransactionId(), transaction.getStatus(), transaction.getTotalAmount(),
                transaction.getPostedAt(), replayed);
    }

    private record PostingAmounts(BigDecimal debits, BigDecimal credits) {
    }
}
