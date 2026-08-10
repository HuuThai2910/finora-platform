package com.finora.loan.integration.profile.contract;

import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import java.time.Instant;
import java.time.LocalDate;

/** Dữ liệu tối thiểu Loan được phép nhận từ boundary profile; không chứa CCCD, ảnh hoặc token. */
public record BorrowerProfileResult(
        String borrowerId,
        LocalDate dateOfBirth,
        Integer age,
        BorrowerKycStatus kycStatus,
        String kycReference,
        String kycVersion,
        IncomeVerificationStatus incomeVerificationStatus,
        BorrowerProfileSource source,
        Instant capturedAt
) {
}
