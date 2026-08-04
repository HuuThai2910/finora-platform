package com.finora.loan.integration.profile;

import com.finora.loan.domain.BorrowerKycStatus;
import com.finora.loan.domain.BorrowerProfileSource;
import com.finora.loan.domain.IncomeVerificationStatus;
import com.finora.loan.exception.LoanBusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** Mock tập trung duy nhất cho local; production không có property này nên không thể vô tình giả VERIFIED. */
@Component
@ConditionalOnProperty(name = "finora.borrower-profile.provider", havingValue = "mock")
public class LocalMockBorrowerProfileProvider implements BorrowerProfileProvider {

    private static final String MOCK_BORROWER_ID = "BORROWER-001";
    private final Clock clock;

    public LocalMockBorrowerProfileProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public BorrowerProfileResult getBorrowerProfile(String borrowerId, LocalDate asOf) {
        if (!MOCK_BORROWER_ID.equals(borrowerId)) {
            throw LoanBusinessException.conflict(
                    "MOCK_BORROWER_NOT_CONFIGURED",
                    "Local mock chỉ được cấu hình cho BORROWER-001"
            );
        }
        return new BorrowerProfileResult(
                borrowerId,
                null,
                30,
                BorrowerKycStatus.VERIFIED,
                "MOCK-KYC-BORROWER-001",
                "MOCK-V1",
                IncomeVerificationStatus.NOT_VERIFIED,
                BorrowerProfileSource.MOCK_USER_PROFILE,
                Instant.now(clock)
        );
    }
}
