package com.finora.loan.service.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Render văn bản dễ đọc nhưng vẫn xác định để cùng điều khoản luôn sinh cùng bytes UTF-8 và SHA-256.
 */
public interface ContractDocumentRenderer {

    /**
     * Văn bản hợp đồng một bên từ hồ sơ và lịch trả nợ cuối. {@code heading} mang số hợp đồng, mã hiển thị và danh tính
     * bên vay đã chụp; hợp đồng cũ chưa có danh tính in theo cách ghi trước V39.
     */
    String render(
            ContractHeading heading,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            String documentVersion,
            Instant expiresAt
    );

    /** Văn bản hợp đồng nhiều bên: văn bản gốc kèm danh sách bên cho vay và phân bổ đã khóa. */
    String renderFunded(
            ContractHeading heading,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            String documentVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash
    );
}
