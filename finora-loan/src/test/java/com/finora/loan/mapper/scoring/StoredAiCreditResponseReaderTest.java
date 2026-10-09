package com.finora.loan.mapper.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import org.junit.jupiter.api.Test;

class StoredAiCreditResponseReaderTest {

    private final StoredAiCreditResponseReader reader = new StoredAiCreditResponseReader(new ObjectMapper());

    @Test
    void readsStoredSnapshot() {
        StoredAiCreditResponse snapshot = reader.read(assessmentWith(
                "{\"riskScore\":95,\"ruleTrace\":[{\"ma\":\"R1\",\"diem\":20}]}"));

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.riskScore()).isEqualTo(95);
        assertThat(snapshot.ruleTrace()).hasSize(1);
    }

    /** Bản ghi hỏng không được làm màn hình đang đọc nó trả lỗi 500. */
    @Test
    void blankOrCorruptSnapshotIsTreatedAsMissing() {
        assertThat(reader.read(assessmentWith(null))).isNull();
        assertThat(reader.read(assessmentWith("  "))).isNull();
        assertThat(reader.read(assessmentWith("{not-json"))).isNull();
    }

    private static CreditScoringAssessment assessmentWith(String json) {
        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getId()).thenReturn(51L);
        when(assessment.getResponseSnapshotJson()).thenReturn(json);
        return assessment;
    }
}
