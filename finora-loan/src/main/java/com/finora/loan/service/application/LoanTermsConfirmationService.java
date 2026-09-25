package com.finora.loan.service.application;

import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.request.ConfirmLoanTermsRequest;
import com.finora.loan.dto.application.request.DeclineLoanTermsRequest;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import java.time.Instant;

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
