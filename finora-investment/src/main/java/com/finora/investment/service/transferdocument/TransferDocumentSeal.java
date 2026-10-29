package com.finora.investment.service.transferdocument;

import java.time.Instant;

/**
 * Con dấu số của FINORA trên giấy xác nhận chuyển nhượng: một chữ ký PAdES-B-B ẩn do chính nền tảng ký, ký ngay trong
 * tiến trình (không có bước xác nhận của người dùng như hợp đồng vay).
 */
public interface TransferDocumentSeal {

    /** Chứng thư demo: PDF phải ghi rõ chữ ký không có giá trị pháp lý. */
    boolean demo();

    String subjectCommonName();

    /**
     * Thêm chữ ký FINORA vào PDF rồi kiểm lại ngay trên bytes vừa tạo; chữ ký hỏng không bao giờ được lưu.
     *
     * @return PDF đã đóng dấu
     */
    byte[] seal(byte[] pdf, Instant now);
}
