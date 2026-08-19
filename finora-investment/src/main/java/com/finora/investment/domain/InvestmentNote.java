package com.finora.investment.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Khoản đầu tư fractional — mỗi MatchResult tạo 1 Note cho investor.
 */
@Entity
@Table(name = "investment_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private LoanListing listing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private MatchResult match;

    @Column(name = "investor_id", nullable = false)
    private Long investorId;

    @Column(name = "principal_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "purchase_date", nullable = false)
    @Builder.Default
    private Instant purchaseDate = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NoteStatus status = NoteStatus.ACTIVE;
}
