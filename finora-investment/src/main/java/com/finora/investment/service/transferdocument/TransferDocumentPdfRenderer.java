package com.finora.investment.service.transferdocument;

/**
 * Dựng PDF A4 "Giấy xác nhận chuyển nhượng Note" từ {@link TransferDocumentContent}. Cùng font DejaVu và bố cục với
 * hợp đồng vay của Loan để hai loại văn bản trông cùng một bộ.
 */
public interface TransferDocumentPdfRenderer {

    byte[] render(TransferDocumentContent content);
}
