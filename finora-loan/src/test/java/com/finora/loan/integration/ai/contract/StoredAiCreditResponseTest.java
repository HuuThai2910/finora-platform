package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot chấm điểm được ghi bằng {@link StoredAiCreditResponse} rồi đọc lại bằng
 * chính nó để phục vụ màn thẩm định. Vòng ghi–đọc phải khép kín: nếu tên trường lệch
 * nhau, phần giải thích trả về toàn null mà không có lỗi nào — đúng kiểu hỏng lặng lẽ
 * mà test này chặn.
 */
class StoredAiCreditResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JSON đúng như một bản ghi thật trong {@code response_snapshot_json}. */
    private static final String SNAPSHOT = """
            {
              "pdProbability": 0.2894,
              "riskScore": 95,
              "evaluationScore": 74.65,
              "creditGrade": "B",
              "recommendation": "APPROVED",
              "borrowerExplanation": {
                "thong_diep": "Hồ sơ đủ điều kiện",
                "ly_do_chinh": ["Không có nợ xấu"],
                "goi_y_cai_thien": ["Giảm dư nợ thẻ"]
              },
              "modelExplanation": {
                "gia_tri_co_so": -1.2,
                "canh_bao": [],
                "yeu_to_bat_loi": [],
                "yeu_to_co_loi": [],
                "tom_tat": {"bat_loi": [], "co_loi": []}
              },
              "ruleTrace": [
                {"ma": "R1", "diem": 20, "toi_da": 20, "thieu_du_lieu": false}
              ],
              "rejectionReasons": [],
              "modelVersion": "17.0.0",
              "decisionPolicyVersion": "CREDIT_POLICY_V1"
            }
            """;

    @Test
    void readsBackEveryExplanationBlockItWrote() throws Exception {
        StoredAiCreditResponse stored = objectMapper.readValue(SNAPSHOT, StoredAiCreditResponse.class);

        assertThat(stored.borrowerExplanation()).isNotNull();
        assertThat(stored.borrowerExplanation().get("thong_diep").asText())
                .isEqualTo("Hồ sơ đủ điều kiện");
        assertThat(stored.modelExplanation()).isNotNull();
        assertThat(stored.modelExplanation().has("tom_tat")).isTrue();
        assertThat(stored.ruleTrace()).isNotNull();
        assertThat(stored.ruleTrace()).hasSize(1);
        assertThat(stored.decisionPolicyVersion()).isEqualTo("CREDIT_POLICY_V1");
    }

    /** Serialize rồi deserialize lại phải giữ nguyên ba khối giải thích. */
    @Test
    void roundTripKeepsExplanationBlocks() throws Exception {
        StoredAiCreditResponse goc = objectMapper.readValue(SNAPSHOT, StoredAiCreditResponse.class);

        StoredAiCreditResponse lai = objectMapper.readValue(
                objectMapper.writeValueAsString(goc), StoredAiCreditResponse.class);

        assertThat(lai.borrowerExplanation()).isEqualTo(goc.borrowerExplanation());
        assertThat(lai.modelExplanation()).isEqualTo(goc.modelExplanation());
        assertThat(lai.ruleTrace()).isEqualTo(goc.ruleTrace());
    }
}
