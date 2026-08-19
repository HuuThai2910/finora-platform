package com.finora.investment.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cấu hình tự động đầu tư — investor đặt quy tắc, hệ thống tự khớp khi có listing mới.
 */
@Entity
@Table(name = "auto_invest_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoInvestConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false, unique = true)
    private Long investorId;

    /** CSV grade filter: "A,B,C". Null = tất cả. */
    @Column(name = "grade_filter", length = 20)
    private String gradeFilter;

    @Column(name = "min_rate", precision = 5, scale = 2)
    private BigDecimal minRate;

    @Column(name = "max_rate", precision = 5, scale = 2)
    private BigDecimal maxRate;

    @Column(name = "min_term")
    private Integer minTerm;

    @Column(name = "max_term")
    private Integer maxTerm;

    @Column(name = "amount_per_note", nullable = false, precision = 15, scale = 2)
    private BigDecimal amountPerNote;

    @Column(name = "total_budget", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalBudget;

    @Column(name = "remaining_budget", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingBudget;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // ── Domain methods ──

    /** Listing có phù hợp filter không. */
    public boolean matchesListing(LoanListing listing) {
        if (!isActive || !listing.isOpen()) return false;
        if (remainingBudget.compareTo(amountPerNote) < 0) return false;

        // Grade filter
        if (gradeFilter != null && !gradeFilter.isEmpty()) {
            boolean found = false;
            for (String g : gradeFilter.split(",")) {
                if (g.trim().equalsIgnoreCase(listing.getGrade())) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }

        // Rate filter
        if (minRate != null && listing.getInterestRate().compareTo(minRate) < 0) return false;
        if (maxRate != null && listing.getInterestRate().compareTo(maxRate) > 0) return false;

        // Term filter
        if (minTerm != null && listing.getTermMonths() < minTerm) return false;
        if (maxTerm != null && listing.getTermMonths() > maxTerm) return false;

        return true;
    }

    /** Trừ budget sau khi đầu tư thành công. */
    public void deductBudget(BigDecimal amount) {
        this.remainingBudget = this.remainingBudget.subtract(amount);
        this.updatedAt = Instant.now();
        if (this.remainingBudget.compareTo(amountPerNote) < 0) {
            this.isActive = false;  // Tạm dừng khi hết budget
        }
    }
}
