package com.finora.payment.domain.repayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.payment.domain.servicing.PaymentLoanAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import com.finora.payment.integration.fineract.PartialPrepaymentCoreSnapshot;
import org.junit.jupiter.api.Test;

class PaymentRepaymentTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    void acceptsBalancedFineractBreakdown() {
        PaymentRepayment repayment = repayment();
        repayment.startCorePosting(NOW);
        repayment.corePosted(new PaymentRepayment.CoreBreakdown(77L, new BigDecimal("800.00"),
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("9200.00"), new BigDecimal("1000.00"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal("10200.00"), BigDecimal.ZERO.setScale(2),
                LocalDate.of(2026, 11, 3), new BigDecimal("1000.00")), NOW);

        assertThat(repayment.getStatus()).isEqualTo(PaymentRepaymentStatus.CORE_POSTED);
        assertThat(repayment.getFineractTransactionId()).isEqualTo(77L);
    }

    @Test
    void rejectsUnbalancedFineractBreakdown() {
        PaymentRepayment repayment = repayment();
        repayment.startCorePosting(NOW);

        assertThatThrownBy(() -> repayment.corePosted(new PaymentRepayment.CoreBreakdown(77L,
                new BigDecimal("799.00"), new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal("9200.00"), new BigDecimal("1000.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("10200.00"),
                BigDecimal.ZERO.setScale(2), null, BigDecimal.ZERO.setScale(2)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không cân");
    }

    @Test
    void acceptsReadOnlyReconciliationResultWithoutStartingAnotherPost() {
        PaymentRepayment repayment = repayment();
        repayment.startCorePosting(NOW);
        repayment.requireReconciliation("OUTCOME_UNKNOWN", "timeout", NOW);

        repayment.corePosted(breakdown(), NOW.plusSeconds(30));

        assertThat(repayment.getStatus()).isEqualTo(PaymentRepaymentStatus.CORE_POSTED);
        assertThat(repayment.getAttemptCount()).isEqualTo(1);
        assertThat(repayment.getErrorCode()).isNull();
    }

    @Test
    void interruptedCorePostingMovesToReadOnlyReconciliationWithoutSecondAttempt() {
        PaymentRepayment repayment = repayment();
        repayment.startCorePosting(NOW);

        repayment.recoverStaleCorePosting(NOW.plusSeconds(60));

        assertThat(repayment.getStatus()).isEqualTo(PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(repayment.getErrorCode()).isEqualTo("CORE_POSTING_INTERRUPTED");
        assertThat(repayment.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recordsOverdueCureAsASeparateBusinessMechanism() {
        PaymentLoanAccount account = PaymentLoanAccount.activate(1L, "LC-1", 2L, "borrower", 3L, "VND", NOW);

        PaymentRepayment repayment = PaymentRepayment.collected("idem-overdue", account,
                new BigDecimal("1000.00"), LocalDate.of(2026, 10, 3),
                PaymentRepaymentType.OVERDUE_CURE, UUID.randomUUID(), NOW);

        assertThat(repayment.getRepaymentType()).isEqualTo(PaymentRepaymentType.OVERDUE_CURE);
    }

    @Test
    void earlySettlementBalancesCoreBreakdownSeparatelyFromPlatformFee() {
        PaymentLoanAccount account = PaymentLoanAccount.activate(1L, "LC-1", 2L, "borrower", 3L, "VND", NOW);
        EarlySettlementQuote quote = EarlySettlementQuote.create(account,
                new EarlySettlementCoreQuote(LocalDate.of(2026, 10, 3), new BigDecimal("1000.00"),
                        new BigDecimal("800.00"), new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), 24, LocalDate.of(2026, 10, 1),
                        LocalDate.of(2028, 10, 1)), new BigDecimal("0.010000"),
                new BigDecimal("100.00"), "POLICY-1", NOW.plusSeconds(900), NOW);
        PaymentRepayment repayment = PaymentRepayment.earlySettlement("idem-early", account, quote,
                UUID.randomUUID(), NOW);
        repayment.startCorePosting(NOW);

        repayment.corePosted(breakdown(), NOW);

        assertThat(repayment.getAmount()).isEqualByComparingTo("1100.00");
        assertThat(repayment.getCoreAmount()).isEqualByComparingTo("1000.00");
        assertThat(repayment.getFeeAmount()).isEqualByComparingTo("100.00");
        assertThat(repayment.getRepaymentType()).isEqualTo(PaymentRepaymentType.EARLY_SETTLEMENT);
    }

    @Test
    void partialPrepaymentKeepsCoreAmountSeparateFromDisclosedPlatformFee() {
        PaymentLoanAccount account = PaymentLoanAccount.activate(1L, "LC-1", 2L, "borrower", 3L,
                "FINORA-FINERACT-V2", "VND", NOW);
        PartialPrepaymentQuote quote = PartialPrepaymentQuote.create(account,
                new PartialPrepaymentCoreSnapshot(LocalDate.of(2026, 10, 3),
                        new BigDecimal("1000.00"), new BigDecimal("9200.00"),
                        new BigDecimal("9000.00"), LocalDate.of(2026, 11, 3),
                        new BigDecimal("1000.00"), 24, LocalDate.of(2026, 10, 1),
                        LocalDate.of(2028, 10, 1)), new BigDecimal("2000.00"),
                new BigDecimal("0.010000"), new BigDecimal("100.00"), "POLICY-1",
                NOW.plusSeconds(900), NOW);

        PaymentRepayment repayment = PaymentRepayment.partialPrepayment("idem-partial", account, quote,
                UUID.randomUUID(), NOW);

        assertThat(repayment.getRepaymentType()).isEqualTo(PaymentRepaymentType.PARTIAL_PREPAYMENT);
        assertThat(repayment.getCoreAmount()).isEqualByComparingTo("3000.00");
        assertThat(repayment.getAmount()).isEqualByComparingTo("3100.00");
        assertThat(repayment.getPartialPrepaymentQuoteId()).isEqualTo(quote.getQuoteId());
    }

    private PaymentRepayment repayment() {
        PaymentLoanAccount account = PaymentLoanAccount.activate(1L, "LC-1", 2L, "borrower", 3L, "VND", NOW);
        return PaymentRepayment.collected("idem", account, new BigDecimal("1000.00"),
                LocalDate.of(2026, 10, 3), UUID.randomUUID(), NOW);
    }

    private PaymentRepayment.CoreBreakdown breakdown() {
        return new PaymentRepayment.CoreBreakdown(77L, new BigDecimal("800.00"),
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("9200.00"), new BigDecimal("1000.00"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal("10200.00"), BigDecimal.ZERO.setScale(2),
                LocalDate.of(2026, 11, 3), new BigDecimal("1000.00"));
    }
}
