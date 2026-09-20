package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm chứng entity InvestmentCommitment.
 */
class InvestmentCommitmentTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

    private InvestmentCommitment newCommitment() {
        return InvestmentCommitment.builder()
                .orderId(1L)
                .listingId(1L)
                .investorId("INVESTOR-001")
                .amount(new BigDecimal("10000000.00"))
                .noteCount(10)
                .noteDenomination(new BigDecimal("1000000.00"))
                .sharePercent(new BigDecimal("10.000000"))
                .status(CommitmentStatus.ACTIVE)
                .paymentHoldReference("HOLD-001")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("Khởi tạo đúng các trường dữ liệu qua Builder")
    void createsWithBuilder() {
        InvestmentCommitment commitment = newCommitment();
        assertThat(commitment.getOrderId()).isEqualTo(1L);
        assertThat(commitment.getInvestorId()).isEqualTo("INVESTOR-001");
        assertThat(commitment.getAmount()).isEqualByComparingTo("10000000.00");
        assertThat(commitment.getNoteCount()).isEqualTo(10);
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.ACTIVE);
    }
}

