package com.finora.investment.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Một Note trong lần khớp: người đang giữ và lịch sử chuyển nhượng (mới nhất trước), cho màn quản trị chợ thứ cấp.
 *
 * @param currentHolderId ID nhà đầu tư đang giữ Note (claim {@code user_id} ở finora-user)
 * @param transfers       mọi lần chuyển nhượng của Note, mới nhất trước
 */
public record AdminNoteOwnershipResponse(
        Long noteId,
        String noteNumber,
        Long listingId,
        String status,
        String currentHolderId,
        String principalAmount,
        String outstandingPrincipal,
        Instant issuedAt,
        List<Transfer> transfers
) {
    /** Một lần chuyển nhượng; {@code tradeReference} là lần khớp tạo ra lần chuyển này. */
    public record Transfer(
            String tradeReference,
            String sellerId,
            String buyerId,
            String price,
            String outstandingAtTransfer,
            Instant transferredAt
    ) {
    }
}
