package com.finora.payment.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPostingCommandTest {

    @Test
    void derivesWalletAccountCodeWithoutAcceptingCallerSuppliedCode() {
        UUID walletId = UUID.fromString("65ec0f3a-df95-44d6-8141-c157b2aa1f1e");
        LedgerPostingEntryCommand entry = new LedgerPostingEntryCommand(
                walletId, null, LedgerBalanceBucket.AVAILABLE, LedgerDirection.CREDIT,
                new BigDecimal("10"));

        assertThat(entry.accountCode())
                .isEqualTo("WALLET:65EC0F3A-DF95-44D6-8141-C157B2AA1F1E:AVAILABLE");
        assertThat(entry.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void requiresClearingEntryToHaveNoWalletAndAValidatedAccount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerPostingEntryCommand(
                UUID.randomUUID(), "SYS_CASH", LedgerBalanceBucket.CLEARING,
                LedgerDirection.DEBIT, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerPostingEntryCommand(
                null, "invalid account", LedgerBalanceBucket.CLEARING,
                LedgerDirection.DEBIT, BigDecimal.ONE));
    }
}
