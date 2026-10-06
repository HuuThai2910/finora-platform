package com.finora.payment.domain.repayment;

import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.integration.fineract.PartialPrepaymentCoreSnapshot;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_partial_prepayment_quotes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartialPrepaymentQuote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "quote_id", nullable = false, unique = true, updatable = false) private UUID quoteId;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false) private String borrowerId;
    @Column(name = "fineract_loan_id", nullable = false, updatable = false) private Long fineractLoanId;
    @Column(name = "core_config_version", nullable = false, length = 50, updatable = false) private String coreConfigVersion;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(name = "transaction_date", nullable = false, updatable = false) private LocalDate transactionDate;
    @Column(name = "scheduled_due", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal scheduledDue;
    @Column(name = "prepaid_principal", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal prepaidPrincipal;
    @Column(name = "core_amount", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal coreAmount;
    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal platformFee;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal totalAmount;
    @Column(name = "outstanding_principal_before", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal outstandingPrincipalBefore;
    @Column(name = "next_due_date_before", updatable = false) private LocalDate nextDueDateBefore;
    @Column(name = "next_due_amount_before", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal nextDueAmountBefore;
    @Column(name = "fee_rate", nullable = false, precision = 7, scale = 6, updatable = false) private BigDecimal feeRate;
    @Column(name = "policy_version", nullable = false, length = 50, updatable = false) private String policyVersion;
    @Column(name = "allocation_strategy", nullable = false, length = 100, updatable = false) private String allocationStrategy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PartialPrepaymentQuoteStatus status;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static PartialPrepaymentQuote create(PaymentLoanAccount account,
            PartialPrepaymentCoreSnapshot core, BigDecimal prepaidPrincipal, BigDecimal feeRate,
            BigDecimal platformFee, String policyVersion, Instant expiresAt, Instant now) {
        BigDecimal principal = prepaidPrincipal.setScale(2);
        BigDecimal coreAmount = core.scheduledDue().add(principal).setScale(2);
        if (principal.signum() <= 0 || coreAmount.compareTo(core.payoffAmount()) >= 0) {
            throw new IllegalArgumentException("Số gốc trả trước phải dương và chưa được tất toán toàn bộ khoản vay");
        }
        PartialPrepaymentQuote quote = new PartialPrepaymentQuote();
        quote.quoteId = UUID.randomUUID();
        quote.loanApplicationId = account.getLoanApplicationId();
        quote.borrowerId = account.getBorrowerId();
        quote.fineractLoanId = account.getFineractLoanId();
        quote.coreConfigVersion = account.getCoreConfigVersion();
        quote.currency = account.getCurrency();
        quote.transactionDate = core.transactionDate();
        quote.scheduledDue = core.scheduledDue().setScale(2);
        quote.prepaidPrincipal = principal;
        quote.coreAmount = coreAmount;
        quote.platformFee = platformFee.setScale(2);
        quote.totalAmount = coreAmount.add(quote.platformFee);
        quote.outstandingPrincipalBefore = core.outstandingPrincipal().setScale(2);
        quote.nextDueDateBefore = core.nextDueDate();
        quote.nextDueAmountBefore = core.nextDueAmount().setScale(2);
        quote.feeRate = feeRate.setScale(6);
        quote.policyVersion = policyVersion;
        quote.allocationStrategy = "advanced-payment-allocation-strategy/REAMORTIZATION";
        quote.status = PartialPrepaymentQuoteStatus.ACTIVE;
        quote.expiresAt = expiresAt;
        quote.createdAt = now;
        quote.updatedAt = now;
        return quote;
    }

    public void consume(String actorId, Instant now) {
        if (!borrowerId.equals(actorId)) throw new IllegalArgumentException("Quote không thuộc borrower");
        if (status != PartialPrepaymentQuoteStatus.ACTIVE) throw new IllegalStateException("Quote không còn hiệu lực");
        if (!now.isBefore(expiresAt)) throw new IllegalStateException("Quote đã hết hạn");
        status = PartialPrepaymentQuoteStatus.CONSUMED;
        consumedAt = now;
        updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return status == PartialPrepaymentQuoteStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
