package com.finora.investment.domain.orderbook.enums;

import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;

/**
 * Chặng của một lần khớp nhìn từ Chợ chuyển nhượng quản trị, gộp trạng thái thanh toán và giấy xác nhận thành một nhãn.
 *
 * <p>Phần vốn đổi chủ ngay lúc khớp; sau đó Payment chuyển tiền ({@link #SETTLING}), FINORA lập và đóng dấu giấy xác
 * nhận ({@link #DOC_PENDING}), rồi neo mã băm ({@link #DONE} khi giấy đã phát hành). Hai trường hợp lỗi (thanh toán
 * {@code FAILED}, giấy {@code FAILED}) không có chặng riêng: thực tế không xảy ra vì tiền đã giữ sẵn, nên được tính vào
 * chặng đang chờ tương ứng và vẫn xử lý qua API thử lại sẵn có.</p>
 */
public enum TradeProgress {
    SETTLING,
    DOC_PENDING,
    DONE;

    /**
     * @param document trạng thái giấy xác nhận; {@code null} khi chưa có dòng văn bản (worker chưa nhận lần khớp)
     */
    public static TradeProgress of(SettlementStatus settlement, NoteTransferDocumentStatus document) {
        if (settlement != SettlementStatus.SETTLED) {
            return SETTLING;
        }
        return document == NoteTransferDocumentStatus.ISSUED ? DONE : DOC_PENDING;
    }
}
