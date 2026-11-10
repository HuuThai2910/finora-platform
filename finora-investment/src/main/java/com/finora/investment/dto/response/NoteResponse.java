package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một chứng chỉ đầu tư đã phát hành.
 */
public record NoteResponse(
        String noteNumber,
        Long loanId,
        String principalAmount,
        String outstandingPrincipal,
        String principalRepaid,
        String interestReceived,
        String annualInterestRate,
        Integer termMonths,
        String status,
        Instant issuedAt,
        // Người đang nắm giữ phần vốn: investorId hiện tại của Note, đã đổi theo các lần chuyển nhượng trên Chợ. API chỉ
        // nằm dưới /investments/admin nên được trả ID người dùng.
        String holderId
) {
}
