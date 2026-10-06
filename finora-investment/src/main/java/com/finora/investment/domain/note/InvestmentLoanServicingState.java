package com.finora.investment.domain.note;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read model tối thiểu từ Loan; không thay thế lịch trả và dư nợ nguồn chuẩn của Fineract/Loan. */
@Entity
@Table(name = "investment_loan_servicing_states")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestmentLoanServicingState {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_application_id", nullable = false, unique = true, updatable = false) private Long loanApplicationId;
    @Column(name = "loan_number", nullable = false, length = 50) private String loanNumber;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "maturity_date") private LocalDate maturityDate;
    @Column(name = "last_reschedule_request_id") private UUID lastRescheduleRequestId;
    @Column(name = "schedule_changed_at") private Instant scheduleChangedAt;
    @Column(name = "settled_at") private Instant settledAt;
    @Column(name = "days_past_due", nullable = false) private int daysPastDue;
    @Column(name = "debt_group", nullable = false) private int debtGroup;
    @Column(name = "overdue_amount", nullable = false, precision = 18, scale = 2) private BigDecimal overdueAmount;
    @Column(name = "total_outstanding", nullable = false, precision = 18, scale = 2) private BigDecimal totalOutstanding;
    @Column(name = "overdue_since") private LocalDate overdueSince;
    @Column(name = "risk_changed_at") private Instant riskChangedAt;
    @Column(name = "risk_data_as_of") private Instant riskDataAsOf;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static InvestmentLoanServicingState rescheduled(Long applicationId, String loanNumber,
            UUID requestId, LocalDate maturityDate, Instant occurredAt, Instant now) {
        InvestmentLoanServicingState value = new InvestmentLoanServicingState();
        value.loanApplicationId = applicationId;
        value.loanNumber = loanNumber;
        value.status = "ACTIVE";
        value.initializeRisk();
        value.applyReschedule(requestId, maturityDate, occurredAt, now);
        value.createdAt = now;
        return value;
    }

    public void applyReschedule(UUID requestId, LocalDate newMaturityDate, Instant occurredAt, Instant now) {
        if (lastRescheduleRequestId != null && lastRescheduleRequestId.equals(requestId)) return;
        if (settledAt != null) throw new IllegalStateException("Không cập nhật lịch cho khoản vay đã tất toán");
        lastRescheduleRequestId = requestId;
        maturityDate = newMaturityDate;
        scheduleChangedAt = occurredAt;
        updatedAt = now;
    }

    public static InvestmentLoanServicingState settled(Long applicationId, String loanNumber,
            Instant settledAt, Instant now) {
        InvestmentLoanServicingState value = new InvestmentLoanServicingState();
        value.loanApplicationId = applicationId;
        value.loanNumber = loanNumber;
        value.status = "SETTLED";
        value.initializeRisk();
        value.settledAt = settledAt;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void settle(Instant value, Instant now) {
        status = "SETTLED";
        settledAt = value;
        updatedAt = now;
    }

    public static InvestmentLoanServicingState delinquency(Long applicationId, String loanNumber,
            int daysPastDue, int debtGroup, BigDecimal overdueAmount, LocalDate overdueSince,
            BigDecimal totalOutstanding, Instant dataAsOf, Instant occurredAt, Instant now) {
        InvestmentLoanServicingState value = new InvestmentLoanServicingState();
        value.loanApplicationId = applicationId;
        value.loanNumber = loanNumber;
        value.status = "ACTIVE";
        value.createdAt = now;
        value.applyDelinquency(daysPastDue, debtGroup, overdueAmount, overdueSince,
                totalOutstanding, dataAsOf, occurredAt, now);
        return value;
    }

    public void applyDelinquency(int newDaysPastDue, int newDebtGroup,
            BigDecimal newOverdueAmount, LocalDate newOverdueSince,
            BigDecimal newTotalOutstanding, Instant dataAsOf, Instant occurredAt, Instant now) {
        if (settledAt != null) return;
        if (riskDataAsOf != null && dataAsOf.isBefore(riskDataAsOf)) return;
        daysPastDue = newDaysPastDue;
        debtGroup = newDebtGroup;
        overdueAmount = newOverdueAmount.setScale(2);
        overdueSince = newOverdueSince;
        totalOutstanding = newTotalOutstanding.setScale(2);
        riskDataAsOf = dataAsOf;
        riskChangedAt = occurredAt;
        updatedAt = now;
    }

    private void initializeRisk() {
        daysPastDue = 0;
        debtGroup = 1;
        overdueAmount = BigDecimal.ZERO.setScale(2);
        totalOutstanding = BigDecimal.ZERO.setScale(2);
    }
}
