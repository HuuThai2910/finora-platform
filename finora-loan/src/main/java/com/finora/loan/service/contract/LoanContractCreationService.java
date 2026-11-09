package com.finora.loan.service.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Lập hợp đồng vay từ hồ sơ đã duyệt và lịch trả nợ cuối, gồm văn bản click-wrap và PDF để ký.
 *
 * <p>Danh tính bên vay ({@code borrowerIdentity}) do nơi gọi đọc từ finora-user trước transaction: phương thức này chạy
 * trong transaction nên không gọi HTTP. Danh tính được chụp lên Contract cùng lần insert để các bản render sau (biên nhận,
 * phát hành lại) in đúng người đã in trên bản ký.</p>
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
            BorrowerIdentity borrowerIdentity,
            Instant now
    );

    /**
     * Tạo một Contract chung cho borrower và toàn bộ lender allocation đã khóa; Contract đang chờ gọi vốn bổ sung thì phát
     * hành lại. {@code borrowerIdentity} chỉ được bỏ trống khi Contract đã có danh tính chụp từ trước.
     */
    LoanContract createFunded(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            Instant expiresAt,
            String actorId,
            BorrowerIdentity borrowerIdentity,
            Instant now
    );
}
