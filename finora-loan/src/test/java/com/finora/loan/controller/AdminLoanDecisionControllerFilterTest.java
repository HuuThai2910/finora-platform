package com.finora.loan.controller;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tham số bảng lọc của {@code GET /api/v1/admin/loan-applications} được đọc đúng kiểu và chuyển nguyên vào service. */
@ExtendWith(MockitoExtension.class)
class AdminLoanDecisionControllerFilterTest {

    @Mock private AdminLoanDecisionService service;
    @Mock private ApplicationNumberLookupService lookupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminLoanDecisionController(service, lookupService)).build();
    }

    @Test
    void advancedParametersBindIntoFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .param("status", "APPROVED")
                        .param("servicing", "OVERDUE")
                        .param("q", "HS-2026")
                        .param("submittedFrom", "2026-11-01")
                        .param("submittedTo", "2026-11-09")
                        .param("amountMin", "10000000")
                        .param("amountMax", "50000000")
                        .param("termMin", "6")
                        .param("termMax", "24")
                        .param("scoreMin", "60")
                        .param("scoreMax", "90")
                        .param("grade", "A", "b")
                        .param("rateMin", "10")
                        .param("rateMax", "18.5")
                        .param("productCode", "VAY_TIEU_DUNG")
                        .param("purposeCode", "EDUCATION")
                        .param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminApplicationFilter> filter = ArgumentCaptor.forClass(AdminApplicationFilter.class);
        verify(service).listApplications(eq(LoanApplicationStatus.APPROVED), eq(null), eq("HS-2026"),
                eq(ApplicationServicingFlag.OVERDUE), filter.capture(), eq(0), eq(10));
        AdminApplicationFilter value = filter.getValue();
        assertThat(value.submittedFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(value.submittedTo()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(value.amountMin()).isEqualByComparingTo("10000000");
        assertThat(value.termMax()).isEqualTo(24);
        assertThat(value.scoreMax()).isEqualByComparingTo("90");
        assertThat(value.grades()).containsExactly("A", "B");
        assertThat(value.rateMax()).isEqualByComparingTo("18.5");
        assertThat(value.productCode()).isEqualTo("VAY_TIEU_DUNG");
        assertThat(value.purposeCode()).isEqualTo(LoanPurpose.EDUCATION);
    }

    @Test
    void noAdvancedParametersGivesEmptyFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loan-applications")).andExpect(status().isOk());

        ArgumentCaptor<AdminApplicationFilter> filter = ArgumentCaptor.forClass(AdminApplicationFilter.class);
        verify(service).listApplications(eq(null), eq(null), eq(null), eq(null), filter.capture(), eq(0), eq(20));
        assertThat(filter.getValue().isEmpty()).isTrue();
    }

    @Test
    void malformedDateIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loan-applications").param("submittedFrom", "09/11/2026"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).listApplications(any(), any(), any(), any(),
                any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
