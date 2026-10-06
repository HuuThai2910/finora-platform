package com.finora.payment.service.repayment;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.payment.domain.servicing.PaymentNoteOwnership;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepaymentAllocatorTest {
    private final RepaymentAllocator allocator = new RepaymentAllocator();

    @Test
    void allocatesProportionallyAndPreservesEveryCent() {
        var first = note(1L, "600.00");
        var second = note(2L, "300.00");
        var third = note(3L, "100.00");

        var result = allocator.allocate(new BigDecimal("333.33"), List.of(first, second, third));

        assertThat(result.get(1L)).isEqualByComparingTo("200.00");
        assertThat(result.get(2L)).isEqualByComparingTo("100.00");
        assertThat(result.get(3L)).isEqualByComparingTo("33.33");
        assertThat(result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("333.33");
    }

    @Test
    void assignsRoundingCentDeterministicallyByRemainderThenNoteId() {
        var result = allocator.allocate(new BigDecimal("0.02"),
                List.of(note(10L, "1.00"), note(11L, "1.00"), note(12L, "1.00")));

        assertThat(result.get(10L)).isEqualByComparingTo("0.01");
        assertThat(result.get(11L)).isEqualByComparingTo("0.01");
        assertThat(result.get(12L)).isEqualByComparingTo("0.00");
    }

    private PaymentNoteOwnership note(Long id, String outstanding) {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        return PaymentNoteOwnership.create(id, "NOTE-" + id, 99L, 8L, "investor-" + id,
                new BigDecimal(outstanding), "VND", now, "TEST", now);
    }
}
