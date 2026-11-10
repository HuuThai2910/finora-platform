package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một dòng danh sách sổ lệnh của Chợ chuyển nhượng quản trị: khoản vay, phần vốn đang lưu hành, giá tốt nhất hai phía,
 * lần khớp gần nhất và trạng thái của sổ.
 *
 * <p>Chỉ trả qua {@code /investments/admin/**}. Không có mã người vay hay nhà đầu tư; giá là % dư nợ gốc còn lại, tiền
 * là chuỗi decimal như các API Investment khác.</p>
 *
 * @param outstandingNotes     số phần vốn còn lưu hành (chưa tất toán)
 * @param outstandingPrincipal tổng dư nợ gốc của các phần vốn đó
 * @param restingBidNotes      số phần vốn đang chờ mua (cộng mọi mức giá)
 * @param state                {@code HAS_ORDERS}, {@code NO_ORDERS} hoặc {@code DEFAULTED} (nợ xấu)
 */
public record AdminOrderBookResponse(
        Long listingId,
        String applicationCode,
        String contractCode,
        String purpose,
        String purposeCode,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        long outstandingNotes,
        String outstandingPrincipal,
        String bestBidPercent,
        String bestAskPercent,
        long restingBidNotes,
        long restingAskNotes,
        String lastTradePercent,
        Instant lastTradeAt,
        String state
) {
}
