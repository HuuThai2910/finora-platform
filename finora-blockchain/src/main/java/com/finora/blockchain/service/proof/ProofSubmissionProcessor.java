package com.finora.blockchain.service.proof;

/**
 * Xử lý một proof đến hạn: claim trong PostgreSQL, gửi ledger đang chạy rồi lưu receipt hoặc lỗi lên proof.
 */
public interface ProofSubmissionProcessor {

    /**
     * Xử lý proof theo ID nội bộ; proof đang được worker khác giữ thì bỏ qua. Lỗi của ledger được ghi lên proof (chờ
     * thử lại hoặc DEAD_LETTER) thay vì ném ra ngoài.
     */
    void process(Long id);
}
