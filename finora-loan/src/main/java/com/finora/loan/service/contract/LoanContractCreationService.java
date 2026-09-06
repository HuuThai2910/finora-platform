package com.finora.loan.service.contract;

import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import java.time.Instant;

public interface LoanContractCreationService {

    /** Tạo đúng một hợp đồng từ final schedule; gọi lặp trả lại hợp đồng đã có của hồ sơ. */
    LoanContract create(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            Instant expiresAt,
            ActorType actorType,
            String actorId,
            String reasonCode,
            Instant now
    );
}
