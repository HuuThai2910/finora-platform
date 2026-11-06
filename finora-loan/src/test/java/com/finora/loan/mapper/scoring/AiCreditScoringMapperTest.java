package com.finora.loan.mapper.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.entity.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.CreditInformationSource;
import com.finora.loan.domain.application.enums.HomeOwnership;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.domain.scoring.entity.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.CreditProfileSource;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.util.HashingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCreditScoringMapperTest {

    private final AiCreditScoringMapper mapper = new AiCreditScoringMapper(
            new HashingService(new ObjectMapper()));

    @Test
    void mapsAiV17ContractAndUsesMaximumInstallmentForEqualPrincipal() {
        LoanApplication application = mock(LoanApplication.class);
        ApplicantFinancialSnapshot financial = mock(ApplicantFinancialSnapshot.class);
        BorrowerEligibilityCheck eligibility = mock(BorrowerEligibilityCheck.class);
        BorrowerCreditProfile profile = mock(BorrowerCreditProfile.class);
        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);

        when(application.getFinancialSnapshot()).thenReturn(financial);
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("50000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal("15.0000"));
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.EQUAL_PRINCIPAL);
        when(financial.getEmploymentLengthMonths()).thenReturn(60);
        when(financial.getAnnualIncomeSnapshot()).thenReturn(new BigDecimal("300000000.00"));
        when(financial.getHomeOwnership()).thenReturn(HomeOwnership.RENT);
        when(financial.getDtiSnapshot()).thenReturn(new BigDecimal("15.5000"));
        when(financial.getInformationSource()).thenReturn(CreditInformationSource.SELF_DECLARED);
        when(application.getBorrowerId()).thenReturn("42");
        when(eligibility.getAge()).thenReturn(30);
        when(eligibility.getIncomeVerificationStatus()).thenReturn(IncomeVerificationStatus.NOT_VERIFIED);
        when(eligibility.getProfileSource()).thenReturn(com.finora.loan.domain.scoring.enums.BorrowerProfileSource.MOCK_USER_PROFILE);
        when(eligibility.getKycVersion()).thenReturn("MOCK-V1");
        when(eligibility.getPolicyVersion()).thenReturn("ELIGIBILITY-V1");
        when(profile.getSource()).thenReturn(com.finora.loan.domain.scoring.enums.CreditProfileSource.FINERACT_INTERNAL);
        when(profile.getCalculationPolicyVersion()).thenReturn("INTERNAL-V1");
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal("4300000.00"));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal("5000000.00"));
        when(schedule.getCalculationPolicyVersion()).thenReturn("FINERACT-V1");

        CreditScoringMapping input = mapper.map(application, eligibility, profile, schedule);

        assertThat(input.request().installment()).isEqualByComparingTo("5000000.00");
        assertThat(input.request().employmentLength()).isEqualTo("5 years");
        assertThat(input.request().interestMethod()).isEqualTo("DECLINING_BALANCE");
        // CCCD không đi qua Loan, nhưng borrower_id thì có: thiếu nó là AI không tra
        // được CIC và mọi hồ sơ bị chấm như người chưa có lịch sử tín dụng.
        assertThat(input.request().citizenIdentityNumber()).isNull();
        assertThat(input.request().borrowerId()).isEqualTo("42");
        assertThat(input.inputJson()).contains("\"borrower_id\":\"42\"");
        assertThat(input.sources().eligibilityPolicyVersion()).isEqualTo("ELIGIBILITY-V1");
        assertThat(input.inputJson()).contains("\"installment\":5000000.00");
        assertThat(input.inputJson()).doesNotContain("delinq_2yrs").doesNotContain("pub_rec");
    }
}
