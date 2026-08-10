package com.finora.loan.dto.scoring.response;

import com.finora.loan.domain.scoring.CreditAssessmentStatus;

public record ScoringRetryAcceptedResponse(
        Long assessmentId,
        String requestStatus,
        CreditAssessmentStatus assessmentStatus,
        String message,
        String resultPath
) {

    private static final String ACCEPTED = "ACCEPTED";
    private static final String ACCEPTED_MESSAGE =
            "Đã tiếp nhận yêu cầu chấm điểm lại. Hãy đọc API chi tiết assessment để xem kết quả cuối cùng.";

    public static ScoringRetryAcceptedResponse accepted(
            Long assessmentId,
            CreditAssessmentStatus assessmentStatus,
            String applicationNumber
    ) {
        return new ScoringRetryAcceptedResponse(
                assessmentId,
                ACCEPTED,
                assessmentStatus,
                ACCEPTED_MESSAGE,
                "/api/v1/admin/loan-applications/%s/assessments/%d"
                        .formatted(applicationNumber, assessmentId)
        );
    }
}
