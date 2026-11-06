package com.finora.loan.service.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Lập hợp đồng vay từ hồ sơ đã duyệt và lịch trả nợ cuối, gồm văn bản click-wrap và PDF để ký.
 */
public interface LoanContractCreationService {

    /**
     * Tạo đúng một hợp đồng từ final schedule; gọi lặp trả lại hợp đồng đã có của hồ sơ.
     *
     * <p>Dùng chung cho auto-approve và admin approve để hai luồng không tự dựng tài liệu khác nhau.
     * Unique application_id là chốt chặn cuối nếu hai worker cạnh tranh tạo cùng hợp đồng.</p>
     */
    LoanContract create(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            Instant expiresAt,
            ActorType actorType,
            String actorId,
            String reasonCode,
            Instant now
    );

    /** Tạo một Contract chung cho borrower và toàn bộ lender allocation đã khóa. */
    LoanContract createFunded(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            Instant expiresAt,
            String actorId,
            Instant now
    );
}
