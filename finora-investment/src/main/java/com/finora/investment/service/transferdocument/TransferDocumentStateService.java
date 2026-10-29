package com.finora.investment.service.transferdocument;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Các bước có transaction của vòng đời giấy xác nhận chuyển nhượng. Dựng PDF, gọi User và ký nằm ngoài các
 * transaction này ({@link TransferDocumentIssuer}) để không giữ khóa dòng trong lúc chờ mạng.
 */
public interface TransferDocumentStateService {

    /** Tạo dòng {@code PENDING} cho các lần khớp vừa thanh toán xong. */
    int enqueueSettled(int batchSize);

    List<Long> dueIds(int batchSize);

    /** Nhận lượt lập văn bản; rỗng khi dòng đã được lượt khác nhận hoặc không còn chờ. */
    Optional<Long> claim(Long documentId);

    /**
     * Lưu PDF đã đóng dấu và ghi event neo hash trong cùng transaction: có văn bản thì chắc chắn có event và ngược
     * lại.
     */
    void complete(Long documentId, byte[] pdf, String hash, JsonNode payload, String sealSubject,
            Instant issuedAt);

    /** Lỗi một lượt: hẹn lượt sau theo backoff tăng dần, hết số lần thì {@code FAILED}. */
    void fail(Long documentId, String error);

    /** Quản trị viên thử lại văn bản đã dừng. */
    void reopen(String tradeReference);
}
