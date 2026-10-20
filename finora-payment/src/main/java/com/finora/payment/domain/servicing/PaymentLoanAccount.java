package com.finora.payment.domain.servicing;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_loan_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLoanAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_application_id", nullable = false, unique = true, updatable = false) private Long loanApplicationId;
    @Column(name = "contract_number", nullable = false, length = 50, unique = true, updatable = false) private String contractNumber;
    @Column(name = "listing_id", nullable = false, updatable = false) private Long listingId;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false) private String borrowerId;
    @Column(name = "fineract_loan_id", nullable = false, unique = true, updatable = false) private Long fineractLoanId;
    @Column(name = "core_config_version", nullable = false, length = 50, updatable = false) private String coreConfigVersion;
    /**
     * Biểu phí trả trước hợp đồng đã công bố, ghi một lần từ {@code LoanDisbursed} (G-POLICY-W2). {@code null} với tài
     * khoản mở trước Payment V14 hoặc hợp đồng không có điều khoản phí: không thu phí trả trước.
     */
    @Column(name = "early_settlement_policy_version", length = 50, updatable = false) private String earlySettlementPolicyVersion;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "activated_at", nullable = false, updatable = false) private Instant activatedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public enum Status { ACTIVE, CLOSED }

    /**
     * Core V4 tính lãi trên dư nợ và số ngày thực tế (D15). Chỉ khoản vay này được trả trước/tất toán tự phục vụ (D17)
     * và có khoản trả kỳ sớm ghi đúng ngày đến hạn (D16); core V1–V3 tính trước lãi cho cả kỳ hạn.
     */
    public boolean dailyInterestCore() {
        return coreConfigVersion != null
                && coreConfigVersion.trim().toUpperCase(java.util.Locale.ROOT).endsWith("V4");
    }

    public static PaymentLoanAccount activate(Long applicationId, String contractNumber, Long listingId,
            String borrowerId, Long fineractLoanId, String coreConfigVersion, String currency, Instant now) {
        return activate(applicationId, contractNumber, listingId, borrowerId, fineractLoanId, coreConfigVersion,
                null, currency, now);
    }

    public static PaymentLoanAccount activate(Long applicationId, String contractNumber, Long listingId,
            String borrowerId, Long fineractLoanId, String coreConfigVersion, String earlySettlementPolicyVersion,
            String currency, Instant now) {
        PaymentLoanAccount account = new PaymentLoanAccount();
        account.loanApplicationId = applicationId;
        account.contractNumber = contractNumber;
        account.listingId = listingId;
        account.borrowerId = borrowerId;
        account.fineractLoanId = fineractLoanId;
        account.coreConfigVersion = coreConfigVersion == null || coreConfigVersion.isBlank()
                ? "FINORA-FINERACT-V1" : coreConfigVersion;
        account.earlySettlementPolicyVersion = earlySettlementPolicyVersion == null || earlySettlementPolicyVersion.isBlank()
                ? null : earlySettlementPolicyVersion.trim();
        account.currency = currency;
        account.status = Status.ACTIVE;
        account.activatedAt = now;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public static PaymentLoanAccount activate(Long applicationId, String contractNumber, Long listingId,
            String borrowerId, Long fineractLoanId, String currency, Instant now) {
        return activate(applicationId, contractNumber, listingId, borrowerId, fineractLoanId,
                "FINORA-FINERACT-V1", currency, now);
    }

    public void close(Instant now) {
        status = Status.CLOSED;
        updatedAt = now;
    }
}
