package com.finora.payment.domain.repayment;

import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_early_settlement_quotes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EarlySettlementQuote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "quote_id", nullable = false, unique = true, updatable = false) private UUID quoteId;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false) private String borrowerId;
    @Column(name = "fineract_loan_id", nullable = false, updatable = false) private Long fineractLoanId;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(name = "transaction_date", nullable = false, updatable = false) private LocalDate transactionDate;
    @Column(name = "principal_portion", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal principalPortion;
    @Column(name = "interest_portion", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal interestPortion;
    @Column(name = "core_fee_portion", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal coreFeePortion;
    @Column(name = "penalty_portion", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal penaltyPortion;
    @Column(name = "core_amount", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal coreAmount;
    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal platformFee;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal totalAmount;
    @Column(name = "fee_rate", nullable = false, precision = 7, scale = 6, updatable = false) private BigDecimal feeRate;
    @Column(name = "policy_version", nullable = false, length = 50, updatable = false) private String policyVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EarlySettlementQuoteStatus status;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static EarlySettlementQuote create(PaymentLoanAccount account, EarlySettlementCoreQuote core,
            BigDecimal feeRate, BigDecimal platformFee, String policyVersion, Instant expiresAt, Instant now) {
        EarlySettlementQuote quote = new EarlySettlementQuote();
        quote.quoteId = UUID.randomUUID();
        quote.loanApplicationId = account.getLoanApplicationId();
        quote.borrowerId = account.getBorrowerId();
        quote.fineractLoanId = account.getFineractLoanId();
        quote.currency = account.getCurrency();
        quote.transactionDate = core.transactionDate();
        quote.principalPortion = core.principal().setScale(2);
        quote.interestPortion = core.interest().setScale(2);
        quote.coreFeePortion = core.fee().setScale(2);
        quote.penaltyPortion = core.penalty().setScale(2);
        quote.coreAmount = core.amount().setScale(2);
        quote.feeRate = feeRate.setScale(6);
        quote.platformFee = platformFee.setScale(2);
        quote.totalAmount = quote.coreAmount.add(quote.platformFee);
        quote.policyVersion = policyVersion;
        quote.status = EarlySettlementQuoteStatus.ACTIVE;
        quote.expiresAt = expiresAt;
        quote.createdAt = now;
        quote.updatedAt = now;
        return quote;
    }

    public void consume(String actorId, Instant now) {
        if (!borrowerId.equals(actorId)) throw new IllegalArgumentException("Quote không thuộc borrower");
        if (status != EarlySettlementQuoteStatus.ACTIVE) throw new IllegalStateException("Quote không còn hiệu lực");
        if (!now.isBefore(expiresAt)) throw new IllegalStateException("Quote đã hết hạn");
        status = EarlySettlementQuoteStatus.CONSUMED;
        consumedAt = now;
        updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return status == EarlySettlementQuoteStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
