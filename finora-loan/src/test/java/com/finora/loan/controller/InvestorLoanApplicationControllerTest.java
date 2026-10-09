package com.finora.loan.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.IntNode;
import com.finora.common.exception.GlobalExceptionHandler;
import com.finora.loan.domain.application.CreditInformationSource;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.Background;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.LoanRequest;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RepaymentCapacity;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RuleResult;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.service.application.InvestorBorrowerProfileService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Hợp đồng JSON mà app nhà đầu tư đọc: tên field, kiểu giá trị luật và lỗi 403 có mã. */
class InvestorLoanApplicationControllerTest {

    private static final String PATH = "/api/v1/investor/loan-applications/{applicationNumber}/borrower-profile";
    private final InvestorBorrowerProfileService service = mock(InvestorBorrowerProfileService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Giống cấu hình Jackson mặc định của Spring Boot: ngày giờ dạng ISO-8601, không phải mảng số.
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new InvestorLoanApplicationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .build();
    }

    @Test
    void exposesFieldNamesTheAppReads() throws Exception {
        when(service.profile("LA-TEST-001")).thenReturn(response());

        mockMvc.perform(get(PATH, "LA-TEST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationNumber").value("LA-TEST-001"))
                .andExpect(jsonPath("$.loan.purposeLabel").value("Chi phí giáo dục"))
                .andExpect(jsonPath("$.loan.expectedDisbursementDate").value("2026-10-20"))
                .andExpect(jsonPath("$.capacity.dtiSnapshot").value(15.0))
                .andExpect(jsonPath("$.background.profileSource").value("MOCK_USER_PROFILE"))
                .andExpect(jsonPath("$.assessment.pdProbability").value(0.3568))
                .andExpect(jsonPath("$.ruleResults[0].field").value("cic_score"))
                .andExpect(jsonPath("$.ruleResults[0].value").value(712))
                .andExpect(jsonPath("$.borrowerId").doesNotExist())
                .andExpect(jsonPath("$.modelExplanation").doesNotExist());
    }

    @Test
    void forbiddenIsJsonWithCode() throws Exception {
        when(service.profile("LA-TEST-001")).thenThrow(LoanBusinessException.forbidden(
                "INVESTOR_ROLE_REQUIRED", "Chỉ tài khoản nhà đầu tư mới xem được hồ sơ người vay"));

        mockMvc.perform(get(PATH, "LA-TEST-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVESTOR_ROLE_REQUIRED"));
    }

    private static InvestorBorrowerProfileResponse response() {
        Instant at = Instant.parse("2026-10-06T05:10:31Z");
        return new InvestorBorrowerProfileResponse(
                "LA-TEST-001",
                new LoanRequest(LoanPurpose.EDUCATION, "Chi phí giáo dục", "Học phí", new BigDecimal("30000000.00"),
                        12, RepaymentMethod.ANNUITY, new BigDecimal("15.5000"), new BigDecimal("2730000.00"),
                        new BigDecimal("2730000.00"), new BigDecimal("32760000.00"), LocalDate.of(2026, 10, 20)),
                new RepaymentCapacity(new BigDecimal("20000000.00"), new BigDecimal("3000000.00"),
                        new BigDecimal("15.00"), 60, CreditInformationSource.SELF_DECLARED, at),
                new Background(30, BorrowerKycStatus.VERIFIED, BorrowerProfileSource.MOCK_USER_PROFILE, at,
                        HomeOwnership.RENT, EducationLevel.UNIVERSITY),
                null,
                new CreditAssessment(new BigDecimal("68.92"), "C", new BigDecimal("0.3568"), 95,
                        LoanDecisionSource.ADMIN, at),
                List.of(new RuleResult("CHARACTER_CIC_HISTORY", "Điểm tín dụng CIC — lịch sử trả nợ", "cic_score",
                        IntNode.valueOf(712), 15, 20, new BigDecimal("1.0"), false)));
    }
}
