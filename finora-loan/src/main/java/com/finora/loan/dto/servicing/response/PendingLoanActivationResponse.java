package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Trạng thái trung gian dành cho chính người vay sau khi hợp đồng đã có hiệu lực nhưng khoản vay
 * chưa được ghi nhận xong vào Fineract. Không trả lỗi nội bộ hay thông tin vận hành nhạy cảm.
 */
public record PendingLoanActivationResponse(
        Long loanApplicationId,
        String applicationNumber,
        String contractNumber,
        BigDecimal principalAmount,
        String currency,
        DisbursementSagaStatus status,
        Instant updatedAt,
        /** Mã hiển thị {@code HS-...}; {@code applicationNumber} vẫn là khóa tra cứu. */
        String applicationCode,
        /** Mã hiển thị {@code HĐ-...}; {@code contractNumber} vẫn là khóa tra cứu. */
        String contractCode
) {
}
