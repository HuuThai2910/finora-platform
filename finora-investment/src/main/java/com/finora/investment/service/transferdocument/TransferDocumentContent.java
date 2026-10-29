package com.finora.investment.service.transferdocument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Toàn bộ dữ liệu in lên giấy xác nhận chuyển nhượng, chụp lại tại lúc lập văn bản. Số tiền lấy từ dòng giao dịch
 * và lịch sử chuyển nhượng đã ghi lúc khớp, không tính lại.
 */
public record TransferDocumentContent(
        String documentNumber,
        String tradeReference,
        Instant executedAt,
        Instant settledAt,
        Instant issuedAt,
        Long loanApplicationId,
        Long listingId,
        String contractNumber,
        BigDecimal annualInterestRate,
        Integer termMonths,
        Party seller,
        Party buyer,
        int pricePermille,
        int quantity,
        BigDecimal amount,
        BigDecimal platformFee,
        BigDecimal sellerProceeds,
        BigDecimal outstandingTotal,
        boolean defaulted,
        List<NoteLine> notes,
        boolean demoSeal
) {
    public TransferDocumentContent {
        notes = List.copyOf(notes);
    }

    /** Một bên của giao dịch; họ tên và CCCD đã che lấy từ finora-user, có thể null khi chưa cập nhật hồ sơ. */
    public record Party(String investorId, String fullName, String maskedIdNumber) {
    }

    public record NoteLine(String noteNumber, BigDecimal outstandingAtTransfer, BigDecimal price, boolean defaulted) {
    }
}
