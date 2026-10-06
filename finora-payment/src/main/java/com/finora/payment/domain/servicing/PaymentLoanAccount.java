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
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "activated_at", nullable = false, updatable = false) private Instant activatedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public enum Status { ACTIVE, CLOSED }

    public static PaymentLoanAccount activate(Long applicationId, String contractNumber, Long listingId,
            String borrowerId, Long fineractLoanId, String coreConfigVersion, String currency, Instant now) {
        PaymentLoanAccount account = new PaymentLoanAccount();
        account.loanApplicationId = applicationId;
        account.contractNumber = contractNumber;
        account.listingId = listingId;
        account.borrowerId = borrowerId;
        account.fineractLoanId = fineractLoanId;
        account.coreConfigVersion = coreConfigVersion == null || coreConfigVersion.isBlank()
                ? "FINORA-FINERACT-V1" : coreConfigVersion;
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
