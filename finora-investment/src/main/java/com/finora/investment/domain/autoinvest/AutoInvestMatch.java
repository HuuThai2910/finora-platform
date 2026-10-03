package com.finora.investment.domain.autoinvest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một lần Auto-Invest xét cấu hình của một nhà đầu tư cho một khoản vay.
 *
 * <p>Unique {@code (investor_id, listing_id)}: dòng này vừa là lịch sử cho app, vừa là chốt
 * chặn để không bao giờ xét lại cùng cặp đó.</p>
 */
@Entity
@Table(name = "auto_invest_matches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutoInvestMatch {

    public enum Outcome { MATCHED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false, length = 100, updatable = false)
    private String investorId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Outcome outcome;

    @Column(length = 50, updatable = false)
    private String reason;

    @Column(precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "order_reference", length = 50, updatable = false)
    private String orderReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AutoInvestMatch matched(String investorId, Long listingId, BigDecimal amount,
                                          String orderReference, Instant now) {
        AutoInvestMatch match = new AutoInvestMatch();
        match.investorId = investorId;
        match.listingId = listingId;
        match.outcome = Outcome.MATCHED;
        match.amount = amount;
        match.orderReference = orderReference;
        match.createdAt = now;
        return match;
    }

    public static AutoInvestMatch skipped(String investorId, Long listingId, String reason,
                                          String orderReference, Instant now) {
        AutoInvestMatch match = new AutoInvestMatch();
        match.investorId = investorId;
        match.listingId = listingId;
        match.outcome = Outcome.SKIPPED;
        match.reason = reason;
        match.orderReference = orderReference;
        match.createdAt = now;
        return match;
    }
}
