package com.finora.loan.service.scoring;

/**
 * Điều phối chấm điểm tín dụng một hồ sơ: xét điều kiện người vay, gọi AI rồi tính lịch trả nợ cuối ở Fineract.
 */
public interface CreditScoringOrchestrator {

    /** Mỗi external call nằm giữa hai transaction local; restart luôn tiếp tục được từ state đã commit. */
    void processApplication(Long applicationId);

    /** Chạy một assessment: gọi AI, tính lịch cuối khi cần rồi lưu kết quả; assessment không cần chạy thì bỏ qua. */
    void executeAssessment(Long assessmentId);
}
