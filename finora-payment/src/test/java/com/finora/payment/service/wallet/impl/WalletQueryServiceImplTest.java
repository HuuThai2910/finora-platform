package com.finora.payment.service.wallet.impl;

import com.finora.payment.domain.ledger.entity.LedgerEntry;
import com.finora.payment.domain.ledger.entity.LedgerTransaction;
import com.finora.payment.domain.ledger.enums.LedgerDirection;
import com.finora.payment.domain.ledger.enums.LedgerTransactionType;
import com.finora.payment.domain.wallet.entity.PaymentWallet;
import com.finora.payment.domain.wallet.enums.WalletOwnerType;
import com.finora.payment.dto.response.WalletTransactionResponse;
import com.finora.payment.repository.ledger.LedgerEntryRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sao kê ví: mỗi giao dịch một dòng cho mỗi chiều tiền, kể cả khi giao dịch ghi nhiều dòng vào cùng ví. */
class WalletQueryServiceImplTest {

    private static final String OWNER = "282";
    private static final UUID WALLET_ID = UUID.randomUUID();

    private final LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
    private final PaymentWalletRepository paymentWalletRepository = mock(PaymentWalletRepository.class);
    private final WalletOwnerResolver walletOwnerResolver = mock(WalletOwnerResolver.class);
    private final WalletQueryServiceImpl service = new WalletQueryServiceImpl(
            mock(WalletAccountService.class), walletOwnerResolver, paymentWalletRepository, ledgerEntryRepository);

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim("user_id", OWNER).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        PaymentWallet wallet = mock(PaymentWallet.class);
        when(wallet.getWalletId()).thenReturn(WALLET_ID);
        when(walletOwnerResolver.currentOwnerType()).thenReturn(WalletOwnerType.INVESTOR);
        when(paymentWalletRepository.findByOwnerTypeAndOwnerIdAndCurrency(WalletOwnerType.INVESTOR, OWNER, "VND"))
                .thenReturn(Optional.of(wallet));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void entriesOfOneTransactionInTheSameWalletBecomeOneLineWithTheirTotal() {
        LedgerTransaction disbursement = transaction(LedgerTransactionType.DISBURSEMENT, Instant.parse("2026-11-01T13:52:36Z"));
        LedgerTransaction deposit = transaction(LedgerTransactionType.DEPOSIT, Instant.parse("2026-11-01T10:00:00Z"));
        List<LedgerEntry> entries = List.of(
                entry(disbursement, LedgerDirection.DEBIT, "5000000.00"),
                entry(disbursement, LedgerDirection.DEBIT, "5000000.00"),
                entry(deposit, LedgerDirection.CREDIT, "20000000.00"));
        when(ledgerEntryRepository.findWalletEntries(eq(WALLET_ID), any())).thenReturn(entries);

        List<WalletTransactionResponse> statement = service.currentStatement();

        assertThat(statement).extracting(WalletTransactionResponse::id)
                .containsExactly(disbursement.getTransactionId(), deposit.getTransactionId());
        assertThat(statement.get(0).amount()).isEqualByComparingTo("10000000");
        assertThat(statement.get(0).direction()).isEqualTo("DEBIT");
        assertThat(statement.get(0).description()).isEqualTo("Giải ngân khoản vay");
        assertThat(statement.get(1).amount()).isEqualByComparingTo("20000000");
    }

    private static LedgerTransaction transaction(LedgerTransactionType type, Instant postedAt) {
        LedgerTransaction transaction = mock(LedgerTransaction.class);
        when(transaction.getTransactionId()).thenReturn(UUID.randomUUID());
        when(transaction.getTransactionType()).thenReturn(type);
        when(transaction.getPostedAt()).thenReturn(postedAt);
        when(transaction.getReferenceId()).thenReturn("REF-" + type);
        return transaction;
    }

    private static LedgerEntry entry(LedgerTransaction transaction, LedgerDirection direction, String amount) {
        LedgerEntry entry = mock(LedgerEntry.class);
        when(entry.getTransaction()).thenReturn(transaction);
        when(entry.getDirection()).thenReturn(direction);
        when(entry.getAmount()).thenReturn(new BigDecimal(amount));
        return entry;
    }
}
