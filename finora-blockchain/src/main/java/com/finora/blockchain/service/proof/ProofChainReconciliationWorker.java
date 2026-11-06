package com.finora.blockchain.service.proof;

/**
 * Worker định kỳ đối chiếu proof đã xác nhận trong PostgreSQL với bản ghi trên Fabric (F08 bước 3–4), bật bằng
 * {@code finora.blockchain.proof.reconciliation-enabled}.
 */
public interface ProofChainReconciliationWorker {

    /** Một lượt: đối chiếu lần lượt các proof Fabric đến hạn kiểm, proof lỗi không chặn proof sau. */
    void reconcile();
}
