package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.investment.domain.note.InvestmentNote;
import com.finora.common.enums.investment.NoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm chứng entity InvestmentNote.
 */
class InvestmentNoteTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

    private InvestmentNote newNote() {
        return InvestmentNote.builder()
                .noteNumber("NOTE-1-1-0001")
                .commitmentId(1L)
                .listingId(1L)
                .loanId(1L)
                .investorId("INVESTOR-001")
                .principalAmount(new BigDecimal("1000000.00"))
                .outstandingPrincipal(new BigDecimal("1000000.00"))
                .principalRepaid(BigDecimal.ZERO)
                .interestReceived(BigDecimal.ZERO)
                .annualInterestRate(new BigDecimal("0.1500"))
                .termMonths(12)
                .sequenceNumber(1)
                .status(NoteStatus.ACTIVE)
                .issuedAt(NOW)
                .createdBy("INVESTOR-001")
                .updatedBy("INVESTOR-001")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("Khởi tạo đúng các trường dữ liệu qua Builder")
    void createsWithBuilder() {
        InvestmentNote note = newNote();

        assertThat(note.getStatus()).isEqualTo(NoteStatus.ACTIVE);
        assertThat(note.getOutstandingPrincipal()).isEqualByComparingTo("1000000.00");
        assertThat(note.getPrincipalRepaid()).isEqualByComparingTo("0.00");
        assertThat(note.getNoteNumber()).isEqualTo("NOTE-1-1-0001");
    }
}

