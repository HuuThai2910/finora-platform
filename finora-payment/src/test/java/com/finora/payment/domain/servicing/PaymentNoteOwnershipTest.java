package com.finora.payment.domain.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentNoteOwnershipTest {

    private static final Instant ISSUED = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void suKienDoiChuDenSauLanTraNoKhongDungLaiDuNoCu() {
        var note = PaymentNoteOwnership.create(1L, "NOTE-1", 99L, 8L, "seller",
                new BigDecimal("1000000.00"), "VND", ISSUED, "ISSUED", ISSUED);

        // Khớp lệnh lúc T1 với dư nợ 1.000.000, Payment phân phối kỳ trả lúc T2 trước khi nhận event.
        note.repayPrincipal(new BigDecimal("100000.00"), Instant.parse("2026-10-05T00:00:00Z"));
        note.apply("buyer", new BigDecimal("1000000.00"), "VND",
                Instant.parse("2026-10-04T00:00:00Z"), "TRD-1", Instant.parse("2026-10-06T00:00:00Z"));

        assertThat(note.getInvestorId()).isEqualTo("buyer");
        assertThat(note.getOutstandingPrincipal()).isEqualByComparingTo("900000.00");
    }

    @Test
    void suKienDoiChuCuHonBanDangCoBiBoQua() {
        var note = PaymentNoteOwnership.create(1L, "NOTE-1", 99L, 8L, "a",
                new BigDecimal("500.00"), "VND", ISSUED, "ISSUED", ISSUED);
        note.apply("c", new BigDecimal("500.00"), "VND",
                Instant.parse("2026-10-03T00:00:00Z"), "TRD-2", Instant.parse("2026-10-03T00:00:00Z"));
        note.apply("b", new BigDecimal("500.00"), "VND",
                Instant.parse("2026-10-02T00:00:00Z"), "TRD-1", Instant.parse("2026-10-04T00:00:00Z"));

        assertThat(note.getInvestorId()).isEqualTo("c");
    }
}
