package com.finora.loan.service.servicing;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.servicing.entity.FinoraLoan;

import java.time.Instant;

/**
 * Tạo khoản vay servicing trong cùng transaction hoàn tất Disbursement Saga.
 */
public interface ServicingActivationService {

    /**
     * Tạo khoản vay và projection từ hợp đồng; gọi lặp trả lại khoản vay đã có nếu cùng hợp đồng và
     * khoản vay core.
     */
    FinoraLoan activate(
            LoanApplication application,
            LoanContract contract,
            Long fineractLoanId,
            Instant disbursedAt
    );
}
