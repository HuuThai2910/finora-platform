package com.finora.loan.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.ApplicantFinancialSnapshot;
import com.finora.loan.domain.BorrowerCreditProfile;
import com.finora.loan.domain.BorrowerEligibilityCheck;
import com.finora.loan.domain.CreditInformationSource;
import com.finora.loan.domain.HomeOwnership;
import com.finora.loan.domain.IncomeVerificationStatus;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.LoanPurpose;
import com.finora.loan.domain.RepaymentMethod;
import com.finora.loan.domain.ScheduleCalculationSnapshot;
import com.finora.loan.service.CreditScoringInput;
import com.finora.loan.service.HashingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCreditScoringMapperTest {

    private final AiCreditScoringMapper mapper = new AiCreditScoringMapper(
            new HashingService(new ObjectMapper()));

    @Test
    void mapsThirteenFieldsAndUsesMaximumInstallmentForEqualPrincipal() {
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
        when(eligibility.getAge()).thenReturn(30);
        when(eligibility.getIncomeVerificationStatus()).thenReturn(IncomeVerificationStatus.NOT_VERIFIED);
        when(eligibility.getProfileSource()).thenReturn(com.finora.loan.domain.BorrowerProfileSource.MOCK_USER_PROFILE);
        when(eligibility.getKycVersion()).thenReturn("MOCK-V1");
        when(profile.getInternalDelinquenciesLast2Years()).thenReturn(2);
        when(profile.getInternalDefaultedLoanCount()).thenReturn(1);
        when(profile.getSource()).thenReturn(com.finora.loan.domain.CreditProfileSource.FINERACT_INTERNAL);
        when(profile.getCalculationPolicyVersion()).thenReturn("INTERNAL-V1");
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal("4300000.00"));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal("5000000.00"));
        when(schedule.getCalculationPolicyVersion()).thenReturn("FINERACT-V1");

        CreditScoringInput input = mapper.map(application, eligibility, profile, schedule);

        assertThat(input.request().installment()).isEqualByComparingTo("5000000.00");
        assertThat(input.request().employmentLength()).isEqualTo("5 years");
        assertThat(input.request().delinquenciesLast2Years()).isEqualTo(2);
        assertThat(input.request().publicRecordProxy()).isEqualTo(1);
        assertThat(input.inputJson()).contains("\"installment\":5000000.00");
    }
}
