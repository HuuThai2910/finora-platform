package com.finora.loan.service.scoring;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.integration.ai.AiCreditIntegrationException;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;

import java.util.List;

/**
 * Các bước ghi database của luồng chấm điểm tín dụng; mỗi bước một transaction local, lời gọi AI và Fineract nằm ngoài.
 */
public interface CreditScoringStateService {

    /** Commit ELIGIBILITY_PENDING trước khi gọi profile provider để restart còn thấy việc chưa hoàn tất. */
    LoanApplication startEligibility(Long applicationId);

    /**
     * Eligible mới tạo immutable input/assessment. Việc này chỉ đụng DB local và kết thúc trước HTTP AI.
     */
    Long applyEligibility(BorrowerEligibilityCheck eligibility);

    /** Lấy lease ngắn trong DB rồi đóng transaction; HTTP AI xảy ra sau khi method trả về. */
    CreditScoringExecution startExecution(Long assessmentId);

    /**
     * Chuẩn bị lãi suất và request lịch cuối bằng scalar; Fineract sẽ được gọi sau khi transaction đóng.
     */
    CreditScoringFinalization prepareFinalization(Long assessmentId, AiCreditScoreResponse response);

    /** Lưu output v17, final schedule, trạng thái và hợp đồng auto-approve trong một transaction local. */
    void complete(
            Long assessmentId,
            AiCreditScoreResponse response,
            CreditScoringFinalization finalization,
            ScheduleCalculationResult scheduleResult
    );

    /** Retry chỉ áp dụng lỗi tạm thời; hết lượt vẫn chuyển manual review và không tạo điểm mặc định. */
    void fail(Long assessmentId, AiCreditIntegrationException failure);

    /** Dùng chung failure state cho AI và bước tính final schedule ở Fineract. */
    void fail(Long assessmentId, String code, String detail, boolean retryable);

    /** Lấy batch hữu hạn hồ sơ mới hoặc chưa xong eligibility, không quét toàn bảng mỗi chu kỳ. */
    List<Long> findApplicationCandidates();

    /** Lấy assessment đến hạn hoặc PROCESSING mất lease để worker khác có thể tiếp quản an toàn. */
    List<Long> findAssessmentCandidates();
}
