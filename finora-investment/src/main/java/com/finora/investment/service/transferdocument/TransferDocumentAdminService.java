package com.finora.investment.service.transferdocument;

import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.dto.response.TransferDocumentAdminResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Tra cứu, tải PDF và thử lại giấy xác nhận chuyển nhượng cho quản trị viên. */
public interface TransferDocumentAdminService {

    Page<TransferDocumentAdminResponse> list(NoteTransferDocumentStatus status, Pageable pageable);

    /**
     * Như trên, thêm ô tìm {@code q}: số giấy, mã lần khớp hoặc mã hồ sơ/hợp đồng của khoản vay, khớp một phần; rỗng thì
     * như trên.
     */
    Page<TransferDocumentAdminResponse> list(NoteTransferDocumentStatus status, String q, Pageable pageable);

    TransferDocumentAdminResponse detail(String tradeReference);

    /** PDF đã đóng dấu, đúng bytes đã lưu lúc phát hành. */
    IssuedPdf pdf(String tradeReference);

    void retry(String tradeReference);

    record IssuedPdf(String documentNumber, byte[] content) {
    }
}
