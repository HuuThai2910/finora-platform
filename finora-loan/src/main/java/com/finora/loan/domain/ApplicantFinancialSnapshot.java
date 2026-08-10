package com.finora.loan.domain;

import com.finora.loan.exception.LoanBusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicantFinancialSnapshot {

    @Column(name = "declared_monthly_income", nullable = false, precision = 18, scale = 2)
    private BigDecimal declaredMonthlyIncome;

    @Column(name = "annual_income_snapshot", nullable = false, precision = 18, scale = 2)
    private BigDecimal annualIncomeSnapshot;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "employment_length_months")
    private Integer employmentLengthMonths;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "education_level", length = 30)
    private EducationLevel educationLevel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "home_ownership", nullable = false, length = 20)
    private HomeOwnership homeOwnership;

    @Column(name = "monthly_debt_obligations", nullable = false, precision = 18, scale = 2)
    private BigDecimal monthlyDebtObligations;

    @Column(name = "dti_snapshot", nullable = false, precision = 9, scale = 4)
    private BigDecimal dtiSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "financial_information_source", nullable = false, length = 30)
    private CreditInformationSource informationSource;

    @Column(name = "financial_information_captured_at", nullable = false)
    private Instant capturedAt;

    /**
     * Đóng băng alternative data tự khai và tính DTI theo phần trăm: debt / income × 100.
     */
    public static ApplicantFinancialSnapshot capture(
            BigDecimal declaredMonthlyIncome,
            Integer employmentLengthMonths,
            EducationLevel educationLevel,
            HomeOwnership homeOwnership,
            BigDecimal monthlyDebtObligations,
            Instant now
    ) {
        if (declaredMonthlyIncome == null || declaredMonthlyIncome.signum() <= 0) {
            throw LoanBusinessException.badRequest("DECLARED_MONTHLY_INCOME_INVALID", "Thu nhập tháng phải lớn hơn 0");
        }
        if (employmentLengthMonths != null && employmentLengthMonths < 0) {
            throw LoanBusinessException.badRequest("EMPLOYMENT_LENGTH_INVALID", "Thâm niên làm việc không được âm");
        }
        if (homeOwnership == null) {
            throw LoanBusinessException.badRequest("HOME_OWNERSHIP_REQUIRED", "Phải chọn tình trạng nhà ở");
        }
        if (monthlyDebtObligations == null || monthlyDebtObligations.signum() < 0) {
            throw LoanBusinessException.badRequest("MONTHLY_DEBT_OBLIGATIONS_INVALID", "Nghĩa vụ nợ tháng không được âm");
        }
        ApplicantFinancialSnapshot snapshot = new ApplicantFinancialSnapshot();
        snapshot.declaredMonthlyIncome = money(declaredMonthlyIncome);
        snapshot.annualIncomeSnapshot = snapshot.declaredMonthlyIncome
                .multiply(BigDecimal.valueOf(12))
                .setScale(2, RoundingMode.HALF_UP);
        snapshot.employmentLengthMonths = employmentLengthMonths;
        snapshot.educationLevel = educationLevel;
        snapshot.homeOwnership = homeOwnership;
        snapshot.monthlyDebtObligations = money(monthlyDebtObligations);
        snapshot.dtiSnapshot = snapshot.monthlyDebtObligations
                .divide(snapshot.declaredMonthlyIncome, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
        snapshot.informationSource = CreditInformationSource.SELF_DECLARED;
        snapshot.capturedAt = now;
        return snapshot;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
