package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tổng quan danh mục đầu tư.
 *
 * <p>Đây là read model dựng từ commitment và Note của chính Investment Service, không
 * join sang service khác (07-service-boundaries.md).</p>
 *
 * @param pendingAmount    vốn đã cam kết nhưng khoản vay chưa giải ngân
 * @param investedAmount   vốn đang nằm trong các Note còn dư nợ
 * @param principalRepaid  tiền gốc đã thu hồi
 * @param interestReceived tiền lãi đã nhận
 */
public record PortfolioResponse(
        String pendingAmount,
        String investedAmount,
        String principalRepaid,
        String interestReceived,
        String totalReceived,
        int activeNoteCount,
        int positionCount,
        BigDecimal weightedAverageRate,
        List<PortfolioPositionResponse> positions
) {
}
