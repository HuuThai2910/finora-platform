package com.finora.blockchain.service.proof;

/**
 * Worker định kỳ gửi proof đến hạn lên ledger đang chạy (bật bằng {@code finora.blockchain.proof.worker-enabled}).
 */
public interface ProofSubmissionWorker {

    /** Một vòng: xử lý lần lượt các proof đến hạn, proof lỗi không chặn proof sau. */
    void processDueProofs();
}
