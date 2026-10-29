package com.finora.investment.service.transferdocument;

/**
 * Lập giấy xác nhận chuyển nhượng cho các lần khớp đã thanh toán: chụp dữ liệu giao dịch, lấy danh tính hai bên từ
 * User, dựng PDF, đóng dấu số rồi giao cho {@link TransferDocumentStateService#complete} lưu cùng event neo hash.
 */
public interface TransferDocumentIssuer {

    /** Một vòng quét của worker. */
    void issueDue(int batchSize);
}
