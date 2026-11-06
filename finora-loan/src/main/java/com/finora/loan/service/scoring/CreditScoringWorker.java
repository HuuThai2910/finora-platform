package com.finora.loan.service.scoring;

/**
 * Worker định kỳ chấm điểm hồ sơ mới và assessment đến hạn (tắt bằng {@code finora.ai.credit.worker-enabled=false}).
 */
public interface CreditScoringWorker {

    /** Batch hữu hạn, không gọi AI theo từng dòng của một API list và không giữ transaction khi chạy vòng lặp. */
    void processDueScoring();
}
