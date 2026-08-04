package com.finora.loan.integration.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.loan.domain.RepaymentMethod;
import com.finora.loan.service.HashingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FineractPayloadMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final FineractPayloadMapper mapper = new FineractPayloadMapper(new HashingService(objectMapper));

    @Test
    void shouldMapFinoraRepaymentMethodsToFineractAmortizationTypes() {
        FineractProductConfiguration annuity = configuration(RepaymentMethod.ANNUITY);
        FineractProductConfiguration equalPrincipal = configuration(RepaymentMethod.EQUAL_PRINCIPAL);

        assertThat(mapper.toCreateProductPayload(annuity))
                .containsEntry("amortizationType", 1)
                .containsEntry("externalId", "FINORA-LP-1-V1")
                .containsEntry("interestRatePerPeriod", new BigDecimal("12.5000"));
        assertThat(mapper.toCreateProductPayload(equalPrincipal))
                .containsEntry("amortizationType", 0);
    }

    @Test
    void shouldReadOnlyRequiredScheduleFieldsIncludingTextualMoney() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"loanSchedule":{"totalPrincipalExpected":"50000000","totalInterestCharged":3000000,
                 "totalFeeChargesCharged":0,"totalPenaltyChargesCharged":0,"totalRepaymentExpected":53000000,
                 "periods":[{"period":1,"fromDate":[2026,8,10],"dueDate":[2026,9,10],"daysInPeriod":31,
                 "principalDue":"50000000","interestDue":3000000,"feeChargesDue":0,"penaltyChargesDue":0,
                 "totalDueForPeriod":53000000,"principalLoanBalanceOutstanding":0}]}}
                """);
        ScheduleCalculationResult result = mapper.toScheduleResult(
                new ScheduleCalculationRequest(
                        1L, 1001L, new BigDecimal("50000000"), 12, new BigDecimal("12.5"),
                        RepaymentMethod.ANNUITY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)),
                2001L,
                response);

        assertThat(result.totalPrincipal()).isEqualByComparingTo("50000000.00");
        assertThat(result.totalRepayment()).isEqualByComparingTo("53000000.00");
        assertThat(result.periods()).hasSize(1);
        assertThat(result.responseHash()).hasSize(64);
        assertThat(mapper.toSchedulePayload(
                new ScheduleCalculationRequest(
                        1L, 1001L, new BigDecimal("50000000"), 12, new BigDecimal("12.5"),
                        RepaymentMethod.ANNUITY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)),
                2001L))
                .containsEntry("clientId", 2001L)
                .containsEntry("loanType", "individual")
                .containsEntry("submittedOnDate", LocalDate.of(2026, 8, 3));
    }

    @Test
    void shouldReconcileOnlyAnExactExternalId() throws Exception {
        JsonNode products = objectMapper.readTree("""
                [
                  {"id": 10, "externalId": "FINORA-LP-1-V10"},
                  {"id": 11, "externalId": "FINORA-LP-1-V1"}
                ]
                """);

        FineractProductCreationResult result = mapper
                .findProductByExternalId(products, "FINORA-LP-1-V1")
                .orElseThrow();

        assertThat(result.resourceId()).isEqualTo(11L);
        assertThat(result.responseSnapshotJson()).contains("FINORA-LP-1-V1", "reconciled");
    }

    @Test
    void shouldResolveOnlyTheExactPreviewClientExternalId() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"pageItems":[
                  {"id": 20, "externalId": "FINORA-PREVIEW-CLIENT-OLD"},
                  {"id": 21, "externalId": "FINORA-PREVIEW-CLIENT"}
                ]}
                """);

        assertThat(mapper.findClientIdByExternalId(response, "FINORA-PREVIEW-CLIENT"))
                .contains(21L);
    }

    private FineractProductConfiguration configuration(RepaymentMethod method) {
        return new FineractProductConfiguration(
                1L, 1L, "PERSONAL_STANDARD", "Vay tiêu dùng",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5000"), method,
                "FINORA-LP-1-V1", "FINORA-FINERACT-V1");
    }
}
