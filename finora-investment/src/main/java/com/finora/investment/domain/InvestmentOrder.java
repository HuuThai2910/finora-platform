package com.finora.investment.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lệnh đầu tư từ investor — đặt vào sàn, chờ matching engine khớp.
 * Optimistic locking qua @Version.
 */
@Entity
@Table(name = "investment_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false)
    private Long investorId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "remaining_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingAmount;

    @Column(name = "min_rate", precision = 5, scale = 2)
    private BigDecimal minRate;

    @Column(name = "max_rate", precision = 5, scale = 2)
    private BigDecimal maxRate;

    /** Bộ lọc grade — CSV: "A,B,C". Null = chấp nhận tất cả. */
    @Column(name = "grade_filter", length = 20)
    private String gradeFilter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // ── Domain methods ──

    /** Trừ remaining khi khớp. */
    public void deductAmount(BigDecimal matchedAmount) {
        this.remainingAmount = this.remainingAmount.subtract(matchedAmount);
        this.updatedAt = Instant.now();
        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingAmount = BigDecimal.ZERO;
            this.status = OrderStatus.MATCHED;
        } else {
            this.status = OrderStatus.PARTIALLY_MATCHED;
        }
    }

    /** Kiểm tra listing có phù hợp filter không. */
    public boolean matchesListing(LoanListing listing) {
        if (!listing.isOpen()) return false;

        // Grade filter
        if (gradeFilter != null && !gradeFilter.isEmpty()) {
            boolean gradeMatch = false;
            for (String g : gradeFilter.split(",")) {
                if (g.trim().equalsIgnoreCase(listing.getGrade())) {
                    gradeMatch = true;
                    break;
                }
            }
            if (!gradeMatch) return false;
        }

        // Rate range filter
        if (minRate != null && listing.getInterestRate().compareTo(minRate) < 0) return false;
        if (maxRate != null && listing.getInterestRate().compareTo(maxRate) > 0) return false;

        return true;
    }

    public boolean isPending() {
        return this.status == OrderStatus.PENDING || this.status == OrderStatus.PARTIALLY_MATCHED;
    }
}
