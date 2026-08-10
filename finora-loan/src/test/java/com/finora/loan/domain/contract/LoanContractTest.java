package com.finora.loan.domain.contract;

import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.exception.LoanDomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanContractTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void borrowerSignsExactDocumentOnlyOnce() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        contract.sign(0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.SIGNED);
        assertThat(contract.getConsentAction()).isEqualTo(ConsentAction.SIGN);
        assertThat(contract.isSameConsent("sign-key", "c".repeat(64), ConsentAction.SIGN)).isTrue();
        assertThatThrownBy(() -> contract.decline(
                0, ContractDeclineReasonCode.OTHER, null, "other-key", "d".repeat(64),
                "BORROWER-001", NOW.plusSeconds(20)))
                .isInstanceOf(LoanDomainException.class)
                .hasMessageContaining("chờ ký");
    }

    @Test
    void wrongOwnerOrDocumentHashCannotSign() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> contract.sign(
                0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                "sign-key", "c".repeat(64), "BORROWER-OTHER", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LOAN_CONTRACT_ACCESS_DENIED");

        assertThatThrownBy(() -> contract.sign(
                0, "0".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CONTRACT_CONTENT_MISMATCH");
    }

    @Test
    void expiryIsIdempotentAndPreventsConsent() {
        LoanContract contract = contract(NOW.plusSeconds(60));

        assertThat(contract.expireIfDue(NOW.plusSeconds(59))).isFalse();
        assertThat(contract.expireIfDue(NOW.plusSeconds(60))).isTrue();
        assertThat(contract.expireIfDue(NOW.plusSeconds(61))).isFalse();
        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.EXPIRED);
    }

    private LoanContract contract(Instant expiresAt) {
        return LoanContract.create(
                "LC-ABCDEF0123456789ABCD",
                1L,
                "BORROWER-001",
                new LoanContractTerms(
                        new BigDecimal("50000000.00"), 12, new BigDecimal("15.0000"),
                        RepaymentMethod.ANNUITY, 2L, new BigDecimal("4000000.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("54000000.00"),
                        new BigDecimal("4500000.00"), new BigDecimal("4500000.00"),
                        "a".repeat(64), LocalDate.of(2026, 8, 20)
                ),
                "LOAN_TERMS_V1",
                "CLICK_WRAP_TEXT_V1",
                "Nội dung hợp đồng",
                "b".repeat(64),
                expiresAt,
                "ADMIN-001",
                NOW
        );
    }
}
