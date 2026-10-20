package com.finora.investment.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Cấu hình Auto-Invest hiện tại. Chưa lưu lần nào thì trả mặc định với {@code enabled=false}.
 */
public record AutoInvestConfigResponse(
        boolean enabled,
        List<String> grades,
        String minAnnualRate,
        Integer maxTermMonths,
        String amountPerLoan,
        Instant enabledAt,
        /** Phiên bản nhà đầu tư đã xác nhận khi bật quy tắc; {@code null} nếu chưa từng xác nhận. */
        String riskAcknowledgedVersion,
        Instant riskAcknowledgedAt,
        /** Phiên bản công bố đang hiệu lực mà client phải hiển thị và gửi lại khi bật. */
        String riskDisclosureVersion,
        /** Đang bật nhưng chưa xác nhận bản công bố hiện hành: worker không đặt lệnh (Q5). */
        boolean pausedForRiskAcknowledgement
) {
}
