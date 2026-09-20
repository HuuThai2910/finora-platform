package com.finora.investment.domain.listing;

import com.finora.common.enums.investment.ListingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity lưu thông tin khoản vay niêm yết trên sàn gọi vốn P2P.
 */
@Entity
@Table(name = "market_listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    @Column(name = "contract_number", nullable = false, length = 50, updatable = false)
    private String contractNumber;

    @Column(name = "product_code", nullable = false, length = 50, updatable = false)
    private String productCode;

    @Column(nullable = false, length = 100, updatable = false)
    private String purpose;

    @Column(nullable = false, length = 100, updatable = false)
    private String region;

    @Column(name = "credit_grade", nullable = false, length = 5, updatable = false)
    private String creditGrade;

    @Column(name = "credit_score", nullable = false, updatable = false)
    private Integer creditScore;

    @Column(name = "target_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal targetAmount;

    @Column(name = "committed_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal committedAmount;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal annualInterestRate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "term_months", nullable = false, updatable = false)
    private Integer termMonths;

    @Column(name = "repayment_method", nullable = false, length = 30, updatable = false)
    private String repaymentMethod;

    @Column(name = "note_denomination", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal noteDenomination;

    @Column(name = "min_investment_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal minInvestmentAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ListingStatus status;

    @Column(name = "funding_round", nullable = false)
    private Integer fundingRound;

    @Column(name = "funding_opened_at", nullable = false, updatable = false)
    private Instant fundingOpenedAt;

    @Column(name = "funding_closes_at", nullable = false, updatable = false)
    private Instant fundingClosesAt;

    @Column(name = "fully_funded_at")
    private Instant fullyFundedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
