package com.finora.loan.integration.fineract.contract;

import java.time.Instant;

/**
 * Một lần chạy job của Fineract ({@code lastRunHistory} hoặc một dòng {@code runhistory}). Fineract 1.15 ghi
 * {@code status} là {@code success}/{@code failed} và serialize thời điểm dạng ISO-8601 UTC.
 *
 * @param status    trạng thái thô của Fineract; chỉ {@code success} được tính là chạy xong
 * @param startedAt lúc bắt đầu, có thể {@code null} nếu Fineract không trả
 * @param endedAt   lúc kết thúc, có thể {@code null} khi job đang chạy dở
 */
public record FineractJobRun(String status, Instant startedAt, Instant endedAt) {

    public boolean succeeded() {
        return "success".equalsIgnoreCase(status);
    }

    /** Mốc dùng để tính độ tươi: lúc kết thúc, thiếu thì lúc bắt đầu. */
    public Instant completedAt() {
        return endedAt != null ? endedAt : startedAt;
    }
}
