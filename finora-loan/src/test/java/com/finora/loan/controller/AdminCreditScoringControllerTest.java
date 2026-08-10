package com.finora.loan.controller;

import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;
import com.finora.loan.dto.scoring.response.ScoringRetryAcceptedResponse;
import com.finora.loan.service.scoring.CreditScoringAssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminCreditScoringControllerTest {

    @Mock
    private CreditScoringAssessmentService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminCreditScoringController(service)).build();
    }

    @Test
    void shouldReturnAcceptedReceiptInsteadOfPreviousAssessmentFailure() throws Exception {
        String applicationNumber = "LA-TEST-001";
        ScoringRetryAcceptedResponse response = ScoringRetryAcceptedResponse.accepted(
                1L, CreditAssessmentStatus.RETRY_PENDING, applicationNumber);
        when(service.retry(
                eq(applicationNumber),
                eq("scoring-retry-002"),
                eq(new ScoringRetryRequest(4L))))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/loan-applications/{applicationNumber}/scoring-retry",
                        applicationNumber)
                        .header("Idempotency-Key", "scoring-retry-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assessmentId").value(1))
                .andExpect(jsonPath("$.requestStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.assessmentStatus").value("RETRY_PENDING"))
                .andExpect(jsonPath("$.message").value(
                        "Đã tiếp nhận yêu cầu chấm điểm lại. Hãy đọc API chi tiết assessment để xem kết quả cuối cùng."))
                .andExpect(jsonPath("$.resultPath").value(
                        "/api/v1/admin/loan-applications/LA-TEST-001/assessments/1"))
                .andExpect(jsonPath("$.failureCode").doesNotExist())
                .andExpect(jsonPath("$.failureDetail").doesNotExist());
    }
}
