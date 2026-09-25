package com.finora.payment.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentWalletTest {

    private static final Instant NOW = Instant.parse("2026-09-21T05:00:00Z");

    @Test
    void appliesAvailableAndHeldDeltaWithoutRounding() {
        PaymentWallet wallet = PaymentWallet.open(
                UUID.randomUUID(), WalletOwnerType.INVESTOR, "INV-001", "vnd", NOW);

        wallet.apply(new BigDecimal("100.00"), BigDecimal.ZERO, NOW.plusSeconds(1));
        wallet.apply(new BigDecimal("-40.00"), new BigDecimal("40.00"), NOW.plusSeconds(2));

        assertThat(wallet.getCurrency()).isEqualTo("VND");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("60.00");
        assertThat(wallet.getHeldBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void rejectsNegativeBalanceAndSubCentAmount() {
        PaymentWallet wallet = PaymentWallet.open(
                UUID.randomUUID(), WalletOwnerType.INVESTOR, "INV-001", "VND", NOW);

        assertThatIllegalArgumentException().isThrownBy(() ->
                wallet.apply(new BigDecimal("-1.00"), BigDecimal.ZERO, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                wallet.apply(new BigDecimal("0.001"), BigDecimal.ZERO, NOW));
    }
}
