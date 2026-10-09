package com.finora.loan.mapper.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Đọc lại snapshot response AI đã lưu trong {@code credit_scoring_assessments.response_snapshot_json},
 * dùng chung cho màn thẩm định của admin và hồ sơ người vay của nhà đầu tư.
 *
 * <p>Ánh xạ về {@link StoredAiCreditResponse} thay vì đọc khoá bằng chuỗi: snapshot được ghi bằng
 * chính record đó nên tên trường phải khớp, và nếu record đổi thì lỗi hiện ra lúc biên dịch chứ không
 * phải bằng các giá trị null trên màn hình.</p>
 *
 * <p>Trả {@code null} khi chưa có; JSON hỏng thì coi như không có thay vì ném lỗi 500, vì một bản
 * ghi lỗi không nên chặn cả màn hình đang đọc nó.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StoredAiCreditResponseReader {

    private final ObjectMapper objectMapper;

    public StoredAiCreditResponse read(CreditScoringAssessment assessment) {
        String json = assessment.getResponseSnapshotJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredAiCreditResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Snapshot chấm điểm không đọc được: assessmentId={}", assessment.getId(), e);
            return null;
        }
    }
}
