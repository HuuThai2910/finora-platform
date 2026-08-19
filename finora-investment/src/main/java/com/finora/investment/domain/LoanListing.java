package com.finora.investment.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Khoản vay được đưa lên sàn P2P — snapshot thông tin tại thời điểm listing.
 */
@Entity
@Table(name = "loan_listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_application_id", nullable = false)
    private Long loanApplicationId;

    @Column(name = "borrower_id", nullable = false)
    private Long borrowerId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "remaining_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingAmount;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(nullable = false, length = 2)
    private String grade;

    @Column(name = "interest_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ListingStatus status = ListingStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // ── Domain methods ──

    /** Trừ remaining khi có lệnh khớp. */
    public void deductAmount(BigDecimal matchedAmount) {
        this.remainingAmount = this.remainingAmount.subtract(matchedAmount);
        this.updatedAt = Instant.now();
        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingAmount = BigDecimal.ZERO;
            this.status = ListingStatus.FUNDED;
        }
    }

    public boolean isOpen() {
        return this.status == ListingStatus.OPEN;
    }
}
