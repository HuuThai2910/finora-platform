package com.finora.loan.integration.profile.client.impl;

import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.integration.profile.client.BorrowerIdentityClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Bản giả lập finora-user cho Loan chạy một mình ({@code finora.borrower-identity.provider=mock}): không có họ tên, không
 * có CCCD, nên hợp đồng ghi "Chưa cập nhật hồ sơ" thay vì một cái tên bịa.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finora.borrower-identity.provider", havingValue = "mock")
public class MockBorrowerIdentityClient implements BorrowerIdentityClient {

    private final Clock clock;

    @Override
    public BorrowerIdentity identity(String borrowerId) {
        return new BorrowerIdentity(null, null, Instant.now(clock));
    }
}
