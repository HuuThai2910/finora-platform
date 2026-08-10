package com.finora.loan.integration.fineract.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FineractPayloadMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HashingService hashingService = new HashingService(objectMapper);
    private final FineractRepaymentPolicy repaymentPolicy = new FineractRepaymentPolicy();
    private final FineractProductPayloadMapper productMapper =
            new FineractProductPayloadMapper(hashingService, repaymentPolicy);
    private final FineractSchedulePayloadMapper scheduleMapper =
            new FineractSchedulePayloadMapper(hashingService, repaymentPolicy);

    @Test
    void shouldMapFinoraRepaymentMethodsToFineractAmortizationTypes() {
        FineractProductConfiguration annuity = configuration(RepaymentMethod.ANNUITY);
        FineractProductConfiguration equalPrincipal = configuration(RepaymentMethod.EQUAL_PRINCIPAL);

        assertThat(productMapper.toCreateProductPayload(annuity))
                .containsEntry("amortizationType", 1)
                .containsEntry("externalId", "FINORA-LP-1-V1")
                .containsEntry("interestRatePerPeriod", new BigDecimal("12.5000"));
        assertThat(productMapper.toCreateProductPayload(equalPrincipal))
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
        ScheduleCalculationResult result = scheduleMapper.toScheduleResult(
                new ScheduleCalculationRequest(
                        1L, 1001L, new BigDecimal("50000000"), 12, new BigDecimal("12.5"),
                        RepaymentMethod.ANNUITY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)),
                2001L,
                response);

        assertThat(result.totalPrincipal()).isEqualByComparingTo("50000000.00");
        assertThat(result.totalRepayment()).isEqualByComparingTo("53000000.00");
        assertThat(result.periods()).hasSize(1);
        assertThat(result.responseHash()).hasSize(64);
        assertThat(scheduleMapper.toSchedulePayload(
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

        FineractProductCreationResult result = productMapper
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

        assertThat(scheduleMapper.findClientIdByExternalId(response, "FINORA-PREVIEW-CLIENT"))
                .contains(21L);
    }

    @Test
    void shouldRejectMissingRequiredMoneyInsteadOfDefaultingToZero() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"loanSchedule":{"totalPrincipalExpected":50000000,"totalInterestCharged":3000000,
                 "totalRepaymentExpected":53000000,"periods":[
                   {"period":1,"fromDate":[2026,8,10],"dueDate":[2026,9,10],
                    "interestDue":3000000,"totalDueForPeriod":53000000,
                    "principalLoanBalanceOutstanding":0}
                 ]}}
                """);

        assertThatThrownBy(() -> scheduleMapper.toScheduleResult(scheduleRequest(), 2001L, response))
                .isInstanceOf(FineractIntegrationException.class)
                .hasMessageContaining("principalDue");
    }

    private FineractProductConfiguration configuration(RepaymentMethod method) {
        return new FineractProductConfiguration(
                1L, 1L, "PERSONAL_STANDARD", "Vay tiêu dùng",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5000"), method,
                "FINORA-LP-1-V1", "FINORA-FINERACT-V1");
    }

    private ScheduleCalculationRequest scheduleRequest() {
        return new ScheduleCalculationRequest(
                1L, 1001L, new BigDecimal("50000000"), 12, new BigDecimal("12.5"),
                RepaymentMethod.ANNUITY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10));
    }
}
