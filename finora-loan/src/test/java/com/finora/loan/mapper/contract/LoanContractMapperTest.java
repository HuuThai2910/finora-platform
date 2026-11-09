package com.finora.loan.mapper.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Danh sách hợp đồng của người vay mang mã hiển thị và mục đích vay, mã tra cứu giữ nguyên tên field cũ. */
class LoanContractMapperTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LoanContractMapper mapper = new LoanContractMapper(new ScheduleCalculationSnapshotMapper(json));

    @Test
    void summaryCarriesDisplayCodesNextToLookupNumbers() {
        LoanContract contract = mock(LoanContract.class);
        when(contract.getContractNumber()).thenReturn("LC-ABCDEF0123456789ABCD");
        when(contract.getContractCode()).thenReturn("HĐ-20261109-K7QM");
        when(contract.getPrincipalAmount()).thenReturn(new BigDecimal("60000000.00"));
        when(contract.getTermMonths()).thenReturn(18);
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-0123456789ABCDEF0123");
        when(application.getApplicationCode()).thenReturn("HS-20261108-3RXA");
        when(application.getPurposeCode()).thenReturn(LoanPurpose.SMALL_BUSINESS);

        LoanContractSummaryResponse summary = mapper.toSummary(contract, application);

        JsonNode body = json.valueToTree(summary);
        assertThat(body.path("contractNumber").asText()).isEqualTo("LC-ABCDEF0123456789ABCD");
        assertThat(body.path("applicationNumber").asText()).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(body.path("contractCode").asText()).isEqualTo("HĐ-20261109-K7QM");
        assertThat(body.path("applicationCode").asText()).isEqualTo("HS-20261108-3RXA");
        assertThat(body.path("purposeCode").asText()).isEqualTo("SMALL_BUSINESS");
    }
}
