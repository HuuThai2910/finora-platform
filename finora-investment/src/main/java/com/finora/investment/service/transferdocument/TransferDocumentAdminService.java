package com.finora.investment.service.transferdocument;

import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import com.finora.investment.dto.response.TransferDocumentAdminResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Tra cứu, tải PDF và thử lại giấy xác nhận chuyển nhượng cho quản trị viên. */
public interface TransferDocumentAdminService {

    Page<TransferDocumentAdminResponse> list(NoteTransferDocumentStatus status, Pageable pageable);

    TransferDocumentAdminResponse detail(String tradeReference);

    /** PDF đã đóng dấu, đúng bytes đã lưu lúc phát hành. */
    IssuedPdf pdf(String tradeReference);

    void retry(String tradeReference);

    record IssuedPdf(String documentNumber, byte[] content) {
    }
}
