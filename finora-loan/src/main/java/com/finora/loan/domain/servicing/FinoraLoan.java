package com.finora.loan.domain.servicing;

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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Aggregate khoản vay đang phục vụ, được tạo đúng một lần sau khi Payment và Fineract giải ngân xong. */
@Entity
@Table(name = "finora_loans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinoraLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_number", nullable = false, unique = true, length = 50, updatable = false)
    private String loanNumber;

    @Column(name = "loan_application_id", nullable = false, unique = true, updatable = false)
    private Long loanApplicationId;

    @Column(name = "application_number", nullable = false, unique = true, length = 50, updatable = false)
    private String applicationNumber;

    @Column(name = "contract_number", nullable = false, unique = true, length = 50, updatable = false)
    private String contractNumber;

    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;

    @Column(name = "fineract_loan_id", nullable = false, unique = true, updatable = false)
    private Long fineractLoanId;

    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal principalAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FinoraLoanStatus status;

    @Column(name = "disbursed_at", nullable = false, updatable = false)
    private Instant disbursedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static FinoraLoan activate(
            String loanNumber,
            Long loanApplicationId,
            String applicationNumber,
            String contractNumber,
            String borrowerId,
            Long fineractLoanId,
            BigDecimal principalAmount,
            String currency,
            Instant disbursedAt
    ) {
        FinoraLoan loan = new FinoraLoan();
        loan.loanNumber = requireText(loanNumber, "loanNumber");
        loan.loanApplicationId = Objects.requireNonNull(loanApplicationId, "loanApplicationId");
        loan.applicationNumber = requireText(applicationNumber, "applicationNumber");
        loan.contractNumber = requireText(contractNumber, "contractNumber");
        loan.borrowerId = requireText(borrowerId, "borrowerId");
        loan.fineractLoanId = Objects.requireNonNull(fineractLoanId, "fineractLoanId");
        loan.principalAmount = money(principalAmount, "principalAmount");
        if (loan.principalAmount.signum() <= 0) {
            throw new IllegalArgumentException("principalAmount phải dương");
        }
        loan.currency = requireText(currency, "currency");
        if (!loan.currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency phải là mã ISO gồm 3 chữ cái");
        }
        loan.status = FinoraLoanStatus.ACTIVE;
        loan.disbursedAt = Objects.requireNonNull(disbursedAt, "disbursedAt");
        loan.createdAt = disbursedAt;
        loan.updatedAt = disbursedAt;
        return loan;
    }

    public void requireOwner(String actorId) {
        if (!borrowerId.equals(requireText(actorId, "actorId"))) {
            throw new IllegalArgumentException("Khoản vay không thuộc người dùng hiện tại");
        }
    }

    public void settle(Instant now) {
        if (status == FinoraLoanStatus.SETTLED) return;
        if (status != FinoraLoanStatus.ACTIVE && status != FinoraLoanStatus.RESTRUCTURING
                && status != FinoraLoanStatus.DEFAULTED) {
            throw new IllegalStateException("Chỉ khoản vay đang hoạt động mới được tất toán");
        }
        status = FinoraLoanStatus.SETTLED;
        closedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public void beginRestructuring(Instant now) {
        if (status == FinoraLoanStatus.RESTRUCTURING) return;
        if (status != FinoraLoanStatus.ACTIVE && status != FinoraLoanStatus.DEFAULTED) {
            throw new IllegalStateException("Chỉ khoản vay đang hoạt động/default mới được cơ cấu");
        }
        status = FinoraLoanStatus.RESTRUCTURING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void completeRestructuring(Instant now) {
        if (status != FinoraLoanStatus.RESTRUCTURING) {
            throw new IllegalStateException("Khoản vay không ở trạng thái cơ cấu");
        }
        status = FinoraLoanStatus.ACTIVE;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markDefaulted(Instant now) {
        if (status == FinoraLoanStatus.DEFAULTED) return;
        if (status != FinoraLoanStatus.ACTIVE && status != FinoraLoanStatus.RESTRUCTURING) {
            throw new IllegalStateException("Không thể chuyển khoản vay hiện tại sang DEFAULTED");
        }
        status = FinoraLoanStatus.DEFAULTED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void cureDefault(Instant now) {
        if (status != FinoraLoanStatus.DEFAULTED) return;
        status = FinoraLoanStatus.ACTIVE;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
