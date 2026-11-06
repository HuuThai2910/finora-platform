package com.finora.loan.service.funding;

import com.finora.loan.domain.application.entity.LoanApplication;

import java.time.Instant;

/**
 * Ghi funding state và event trong đúng transaction đã chốt terms.
 */
public interface LoanFundingRequestService {

    /**
     * Mở đợt gọi vốn của hồ sơ và ghi outbox {@code LoanFundingRequested}; hồ sơ đã mở gọi vốn thì
     * không ghi lại. Phải gọi trong transaction đang mở.
     */
    void request(LoanApplication application, Instant now);
}
