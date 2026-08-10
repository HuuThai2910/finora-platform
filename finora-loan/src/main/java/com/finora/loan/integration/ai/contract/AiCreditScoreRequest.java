package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.loan.domain.application.HomeOwnership;
import java.math.BigDecimal;

/** Contract đúng 13 field runtime của AI v10; không chứa target huấn luyện hoặc FICO/CIC. */
public record AiCreditScoreRequest(
        @JsonProperty("person_age") Integer personAge,
        @JsonProperty("emp_length") String employmentLength,
        @JsonProperty("annual_inc") BigDecimal annualIncome,
        @JsonProperty("loan_amnt") BigDecimal loanAmount,
        @JsonProperty("home_ownership") String homeOwnership,
        String purpose,
        @JsonProperty("int_rate") BigDecimal annualInterestRate,
        @JsonProperty("term_months") Integer termMonths,
        @JsonProperty("verification_status") String verificationStatus,
        BigDecimal dti,
        @JsonProperty("delinq_2yrs") Integer delinquenciesLast2Years,
        @JsonProperty("pub_rec") Integer publicRecordProxy,
        BigDecimal installment
) {
}
