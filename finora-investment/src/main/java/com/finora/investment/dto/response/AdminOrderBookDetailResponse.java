package com.finora.investment.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Chi tiết một sổ lệnh cho Chợ chuyển nhượng quản trị: khoản vay và phần vốn, tình hình trả nợ theo bản chiếu của
 * Investment, số liệu chuyển nhượng và số lần khớp theo chặng. Độ sâu sổ lấy từ ảnh chụp công khai
 * ({@code GET /investments/order-books/{listingId}} và kênh WebSocket), không lặp ở đây.
 *
 * @param borrowerId         mã người vay ở finora-user, chỉ để web mở hồ sơ khách hàng (không hiển thị)
 * @param borrowerFullName   họ tên người vay; null khi User chưa đọc được
 * @param outstandingPerNote dư nợ gốc lớn nhất của một phần vốn còn lưu hành (cơ sở tính giá)
 * @param loanNumber         mã khoản vay ở Loan ({@code LN-…}) để web đọc kỳ trả; null khi Investment chưa nhận sự kiện
 *                           giải ngân của khoản vay này
 * @param daysPastDue        số ngày quá hạn theo sự kiện Loan gửi gần nhất; null khi chưa có bản chiếu
 * @param maturityDate       ngày đáo hạn (tất toán dự kiến); null khi chưa có bản chiếu
 * @param transferredNotes   số phần vốn đã đổi chủ ít nhất một lần
 * @param holderCount        số nhà đầu tư đang nắm giữ phần vốn còn lưu hành
 * @param feeCollected       phí nền tảng của các lần khớp đã thanh toán
 * @param settlingTrades     lần khớp chưa thanh toán xong (gồm cả lần lỗi chờ đối soát)
 * @param documentPendingTrades lần khớp đã thanh toán, giấy xác nhận chưa phát hành
 * @param completedTrades    lần khớp đã có giấy xác nhận
 */
public record AdminOrderBookDetailResponse(
        Long listingId,
        String applicationCode,
        String contractCode,
        String purpose,
        String purposeCode,
        String borrowerId,
        String borrowerFullName,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        String noteDenomination,
        Instant notesIssuedAt,
        boolean defaulted,
        long issuedNotes,
        long outstandingNotes,
        String outstandingPerNote,
        String loanNumber,
        Integer daysPastDue,
        LocalDate maturityDate,
        long transferredNotes,
        long holderCount,
        String feeCollected,
        long settlingTrades,
        long documentPendingTrades,
        long completedTrades
) {
}
