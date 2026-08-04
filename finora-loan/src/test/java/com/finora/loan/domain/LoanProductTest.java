package com.finora.loan.domain;

import com.finora.loan.exception.LoanBusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanProductTest {

    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");

    @Test
    void shouldCreateFixedRateDraftNotSynced() {
        LoanProduct product = product("personal_standard");

        assertThat(product.getCode()).isEqualTo("PERSONAL_STANDARD");
        assertThat(product.getAnnualInterestRate()).isEqualByComparingTo("12.5000");
        assertThat(product.getRepaymentMethod()).isEqualTo(RepaymentMethod.ANNUITY);
        assertThat(product.getStatus()).isEqualTo(LoanProductStatus.DRAFT);
        assertThat(product.getCoreSyncStatus()).isEqualTo(CoreSyncStatus.NOT_SYNCED);
    }

    @Test
    void shouldRequireSuccessfulCoreMappingBeforeActivation() {
        LoanProduct product = product("PERSONAL_STANDARD");

        assertThatThrownBy(() -> product.activate(0, "ADMIN-1", NOW.plusSeconds(1)))
                .isInstanceOf(LoanBusinessException.class)
                .extracting("code")
                .isEqualTo("CORE_PRODUCT_NOT_SYNCED");

        product.markCoreSyncPending(0, "ADMIN-1", NOW.plusSeconds(2));
        product.markCoreSynced(10L, "ADMIN-1", NOW.plusSeconds(3));
        product.activate(0, "ADMIN-1", NOW.plusSeconds(4));

        assertThat(product.getStatus()).isEqualTo(LoanProductStatus.ACTIVE);
        assertThat(product.getCurrentCoreMappingId()).isEqualTo(10L);
    }

    @Test
    void shouldRejectAmountOutsideConfiguredRange() {
        assertThatThrownBy(() -> product("PERSONAL_STANDARD")
                .requireRequestedTerms(new BigDecimal("9999999"), 12))
                .isInstanceOf(LoanBusinessException.class)
                .extracting("code")
                .isEqualTo("LOAN_TERMS_OUT_OF_RANGE");
    }

    private LoanProduct product(String code) {
        return LoanProduct.create(
                code, "Vay tiêu dùng tiêu chuẩn", "Mô tả",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5"), RepaymentMethod.ANNUITY,
                "ADMIN-1", NOW);
    }
}
