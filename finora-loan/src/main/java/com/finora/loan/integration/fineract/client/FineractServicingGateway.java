package com.finora.loan.integration.fineract.client;

import com.finora.loan.domain.servicing.LoanServicingHistory;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import java.time.LocalDate;

public interface FineractServicingGateway {
    LoanServicingSnapshot read(Long fineractLoanId, LocalDate businessDate);

    /** Giao dịch và lịch trả của khoản vay, để thống kê tính lại dư nợ và quá hạn của các ngày đã qua. */
    LoanServicingHistory readHistory(Long fineractLoanId);
}
