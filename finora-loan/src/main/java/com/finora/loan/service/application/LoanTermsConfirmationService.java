package com.finora.loan.service.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.response.LoanApplicationResponse;

import java.time.Instant;

/**
 * Xác nhận điều khoản sau duyệt: chuẩn bị bằng chứng điều khoản cuối và nhận chấp nhận/từ chối của người vay.
 */
public interface LoanTermsConfirmationService {

    /** Chuẩn bị evidence điều khoản sau duyệt và chỉ tạo Contract ngay khi điều khoản không bất lợi. */
    LoanContract prepareAfterApproval(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            Instant termsExpiresAt,
            ActorType actorType,
            String actorId,
            Instant now
    );

    LoanApplicationResponse accept(
            String applicationNumber,
            String idempotencyKey,
            ConfirmLoanTermsRequest request
    );

    LoanApplicationResponse decline(
            String applicationNumber,
            String idempotencyKey,
            DeclineLoanTermsRequest request
    );
}
