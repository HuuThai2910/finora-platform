package com.finora.loan.mapper.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B73: màn duyệt hồ sơ cần ID số của hồ sơ để mở explorer bằng chứng ({@code LOAN_APPLICATION} tra theo ID) và số
 * hợp đồng khi Contract đã lập.
 */
class AdminLoanDecisionMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final String CONTRACT_NUMBER = "LC-1A2B3C4D5E6F7A8B9C0D";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AdminLoanDecisionMapper mapper = new AdminLoanDecisionMapper(new ScheduleCalculationSnapshotMapper(json));

    @Test
    void reviewDetailCarriesNumericIdAndContractNumber() {
        AdminLoanReviewDetailResponse detail = mapper.toDetail(
                application(), null, null, null, null, null, List.of(), CONTRACT_NUMBER);

        assertThat(detail.id()).isEqualTo(7L);
        assertThat(detail.applicationNumber()).isEqualTo("LA-TEST-001");
        assertThat(detail.status()).isEqualTo(LoanApplicationStatus.APPROVED);
        assertThat(detail.contractNumber()).isEqualTo(CONTRACT_NUMBER);

        // Web đọc đúng hai tên field này để dựng link proof.
        JsonNode body = json.valueToTree(detail);
        assertThat(body.path("id").asLong()).isEqualTo(7L);
        assertThat(body.path("contractNumber").asText()).isEqualTo(CONTRACT_NUMBER);
    }

    @Test
    void reviewDetailHasNullContractNumberBeforeContractExists() {
        AdminLoanReviewDetailResponse detail = mapper.toDetail(
                application(), null, null, null, null, null, List.of(), null);

        assertThat(detail.id()).isEqualTo(7L);
        assertThat(detail.contractNumber()).isNull();
        assertThat(json.valueToTree(detail).has("contractNumber")).isTrue();
    }

    private static LoanApplication application() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(7L);
        when(application.getApplicationNumber()).thenReturn("LA-TEST-001");
        when(application.getBorrowerId()).thenReturn("BORROWER-42");
        when(application.getStatus()).thenReturn(LoanApplicationStatus.APPROVED);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        return application;
    }
}
