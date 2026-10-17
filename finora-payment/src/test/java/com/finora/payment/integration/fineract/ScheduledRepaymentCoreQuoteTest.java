package com.finora.payment.integration.fineract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ScheduledRepaymentCoreQuoteTest {

    @Test
    void installmentNotYetOverdueIsTheOnlyPayableAmount() {
        var quote = new ScheduledRepaymentCoreQuote(money("1726000"), money("0"));

        assertThat(quote.payableAmount()).isEqualByComparingTo("1726000");
        assertThat(quote.partialOverdueAmount()).isNull();
        assertThat(quote.accepts(money("1726000"))).isTrue();
        assertThat(quote.accepts(money("1000000"))).isFalse();
    }

    @Test
    void singleOverdueInstallmentIsCuredByTheTemplateAmount() {
        // Template gồm cả kỳ quá hạn và phần lãi/phí của chính kỳ đó: số lớn hơn vẫn là số khắc phục.
        var quote = new ScheduledRepaymentCoreQuote(money("1725000"), money("125000"));

        assertThat(quote.payableAmount()).isEqualByComparingTo("1725000");
        assertThat(quote.partialOverdueAmount()).isNull();
        assertThat(quote.accepts(money("125000"))).isFalse();
    }

    @Test
    void severalOverdueInstallmentsAreCuredInFullOrPaidDownByTheOldestInstallment() {
        // B31: DPD 91, ba kỳ quá hạn. Template Fineract chỉ trả kỳ cũ nhất (1.726.000), totalOverdue là 5.178.000.
        var quote = new ScheduledRepaymentCoreQuote(money("1726000"), money("5178000"));

        assertThat(quote.payableAmount()).isEqualByComparingTo("5178000");
        assertThat(quote.partialOverdueAmount()).isEqualByComparingTo("1726000");
        assertThat(quote.accepts(money("5178000"))).isTrue();
        assertThat(quote.accepts(money("1726000"))).isTrue();
        assertThat(quote.accepts(money("3452000"))).isFalse();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
