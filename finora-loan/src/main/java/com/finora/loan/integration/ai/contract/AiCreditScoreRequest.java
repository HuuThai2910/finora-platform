package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Contract request của AI v17; installment và base rate đều do Loan/Fineract cung cấp. */
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
        BigDecimal installment,
        @JsonProperty("interest_method") String interestMethod,
        @JsonProperty("so_cccd") String citizenIdentityNumber,
        /**
         * ID hồ sơ người vay bên finora-user. AI Service dùng giá trị này để tự hỏi
         * finora-user lấy CCCD rồi tra CIC, nhờ đó Loan không phải giữ PII.
         */
        @JsonProperty("borrower_id") String borrowerId
) {
}
