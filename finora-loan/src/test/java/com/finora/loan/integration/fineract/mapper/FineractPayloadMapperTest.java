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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FineractPayloadMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HashingService hashingService = new HashingService(objectMapper);
    private final FineractRepaymentPolicy repaymentPolicy = new FineractRepaymentPolicy();
    private final FineractProductPayloadMapper productMapper =
            new FineractProductPayloadMapper(hashingService, repaymentPolicy, null);
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
                        RepaymentMethod.ANNUITY, "FINORA-FINERACT-V1",
                        LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)),
                2001L,
                response);

        assertThat(result.totalPrincipal()).isEqualByComparingTo("50000000.00");
        assertThat(result.totalRepayment()).isEqualByComparingTo("53000000.00");
        assertThat(result.periods()).hasSize(1);
        assertThat(result.responseHash()).hasSize(64);
        assertThat(scheduleMapper.toSchedulePayload(
                new ScheduleCalculationRequest(
                        1L, 1001L, new BigDecimal("50000000"), 12, new BigDecimal("12.5"),
                        RepaymentMethod.ANNUITY, "FINORA-FINERACT-V1",
                        LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)),
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

    @Test
    void shouldCreateV2ProductWithAdvancedReamortizationAllocation() {
        FineractProductConfiguration v2 = new FineractProductConfiguration(
                1L, 2L, "PERSONAL_V2", "Vay tiêu dùng V2",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5000"), RepaymentMethod.ANNUITY,
                "FINORA-LP-1-V2", "FINORA-FINERACT-V2");

        var payload = productMapper.toCreateProductPayload(v2);

        assertThat(payload).containsEntry("transactionProcessingStrategyCode",
                        "advanced-payment-allocation-strategy")
                .containsEntry("loanScheduleType", "PROGRESSIVE");
        assertThat(payload.get("paymentAllocation")).isEqualTo(List.of(Map.of(
                "transactionType", "DEFAULT",
                "futureInstallmentAllocationRule", "REAMORTIZATION",
                "paymentAllocationOrder", List.of(
                        allocation("PAST_DUE_PRINCIPAL", 1),
                        allocation("PAST_DUE_INTEREST", 2),
                        allocation("PAST_DUE_FEE", 3),
                        allocation("PAST_DUE_PENALTY", 4),
                        allocation("DUE_PRINCIPAL", 5),
                        allocation("DUE_INTEREST", 6),
                        allocation("DUE_FEE", 7),
                        allocation("DUE_PENALTY", 8),
                        allocation("IN_ADVANCE_PRINCIPAL", 9),
                        allocation("IN_ADVANCE_INTEREST", 10),
                        allocation("IN_ADVANCE_FEE", 11),
                        allocation("IN_ADVANCE_PENALTY", 12)))));
    }

    @Test
    void shouldCreateV3ProductWithNextInstallmentAllocationToShortenRemainingTerm() {
        FineractProductConfiguration v3 = new FineractProductConfiguration(
                1L, 3L, "PERSONAL_V3", "Vay tiêu dùng V3",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5000"), RepaymentMethod.ANNUITY,
                "FINORA-LP-1-V3", "FINORA-FINERACT-V3");

        var payload = productMapper.toCreateProductPayload(v3);

        assertThat(payload).containsEntry("transactionProcessingStrategyCode",
                        "advanced-payment-allocation-strategy")
                .containsEntry("loanScheduleType", "PROGRESSIVE");
        @SuppressWarnings("unchecked")
        var allocations = (List<Map<String, Object>>) payload.get("paymentAllocation");
        assertThat(allocations).singleElement().satisfies(rule ->
                assertThat(rule).containsEntry("futureInstallmentAllocationRule", "NEXT_INSTALLMENT"));
    }

    @Test
    void shouldCreateV4ProductWithDailyInterestRecalculationSoPrepaymentNeverChargesFutureInterest() {
        FineractProductConfiguration v4 = new FineractProductConfiguration(
                1L, 4L, "PERSONAL_V4", "Vay tiêu dùng V4",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.0000"), RepaymentMethod.ANNUITY,
                "FINORA-LP-1-V4", "FINORA-FINERACT-V4");

        var payload = productMapper.toCreateProductPayload(v4);

        // D15 (B28/B29): lãi theo dư nợ và số ngày thực tế; tất toán tính lãi đến ngày tất toán.
        assertThat(payload)
                .containsEntry("isInterestRecalculationEnabled", true)
                .containsEntry("interestCalculationPeriodType", 0)
                .containsEntry("interestRecalculationCompoundingMethod", 0)
                .containsEntry("rescheduleStrategyMethod", 4)
                .containsEntry("recalculationRestFrequencyType", 2)
                .containsEntry("recalculationRestFrequencyInterval", 1)
                .containsEntry("preClosureInterestCalculationStrategy", 1)
                .containsEntry("isArrearsBasedOnOriginalSchedule", false)
                .containsEntry("loanScheduleType", "PROGRESSIVE");
        @SuppressWarnings("unchecked")
        var allocations = (List<Map<String, Object>>) payload.get("paymentAllocation");
        assertThat(allocations).singleElement().satisfies(rule ->
                assertThat(rule).containsEntry("futureInstallmentAllocationRule", "NEXT_INSTALLMENT"));
        // V3 giữ nguyên cách cũ để sản phẩm đang có không đổi nghĩa khi đối chiếu lại.
        var v3 = productMapper.toCreateProductPayload(new FineractProductConfiguration(
                1L, 3L, "PERSONAL_V3", "Vay tiêu dùng V3",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.0000"), RepaymentMethod.ANNUITY,
                "FINORA-LP-1-V3", "FINORA-FINERACT-V3"));
        assertThat(v3).containsEntry("isInterestRecalculationEnabled", false)
                .containsEntry("interestCalculationPeriodType", 1)
                .doesNotContainKey("preClosureInterestCalculationStrategy");
    }

    @Test
    void shouldCreateV4EqualPrincipalProductWithCumulativeScheduleAndStandardStrategy() {
        FineractProductConfiguration equalPrincipal = new FineractProductConfiguration(
                2L, 1L, "BUSINESS_EQUAL_PRINCIPAL", "Vay kinh doanh gốc trả đều",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.0000"), RepaymentMethod.EQUAL_PRINCIPAL,
                "FINORA-LP-2-V1", "FINORA-FINERACT-V4");

        var productPayload = productMapper.toCreateProductPayload(equalPrincipal);
        var schedulePayload = scheduleMapper.toSchedulePayload(new ScheduleCalculationRequest(
                2L, 2002L, new BigDecimal("50000000"), 12, new BigDecimal("12"),
                RepaymentMethod.EQUAL_PRINCIPAL, "FINORA-FINERACT-V4",
                LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 1)), 2001L);

        assertThat(productPayload)
                .containsEntry("amortizationType", 0)
                .containsEntry("transactionProcessingStrategyCode", "mifos-standard-strategy")
                .containsEntry("isInterestRecalculationEnabled", true)
                .containsEntry("interestCalculationPeriodType", 0)
                .containsEntry("rescheduleStrategyMethod", 2)
                .doesNotContainKeys("loanScheduleType", "paymentAllocation");
        assertThat(schedulePayload)
                .containsEntry("amortizationType", 0)
                .containsEntry("transactionProcessingStrategyCode", "mifos-standard-strategy")
                .containsEntry("interestCalculationPeriodType", 0);
    }

    @Test
    void scheduleRequestUsesTheSameInterestCalculationAsTheProductVersion() {
        var v4 = scheduleMapper.toSchedulePayload(new ScheduleCalculationRequest(
                1L, 1001L, new BigDecimal("10000000"), 6, new BigDecimal("12"),
                RepaymentMethod.ANNUITY, "FINORA-FINERACT-V4",
                LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17)), 2001L);
        var v3 = scheduleMapper.toSchedulePayload(new ScheduleCalculationRequest(
                1L, 1001L, new BigDecimal("10000000"), 6, new BigDecimal("12"),
                RepaymentMethod.ANNUITY, "FINORA-FINERACT-V3",
                LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17)), 2001L);

        // Lệch kiểu tính lãi giữa khoản vay và Product làm Fineract từ chối tính lịch.
        assertThat(v4).containsEntry("interestCalculationPeriodType", 0);
        assertThat(v3).containsEntry("interestCalculationPeriodType", 1);
    }

    @Test
    void shouldRejectReconciledV4ProductWhenInterestRecalculationIsOff() throws Exception {
        String json = """
                {
                  "amortizationType": {"id": 1},
                  "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.progressive", "value": "Progressive"},
                  "isInterestRecalculationEnabled": RECALC,
                  "paymentAllocation": [{
                    "transactionType": "DEFAULT",
                    "futureInstallmentAllocationRule": "NEXT_INSTALLMENT",
                    "paymentAllocationOrder": [{},{},{},{},{},{},{},{},{},{},{},{}]
                  }]
                }
                """;

        // Product cùng externalId nhưng tắt tính lại lãi sẽ thu lãi tương lai: không được tái sử dụng.
        assertThatThrownBy(() -> productMapper.validateReconciledProduct(
                objectMapper.readTree(json.replace("RECALC", "false")), "FINORA-FINERACT-V4",
                RepaymentMethod.ANNUITY))
                .isInstanceOf(FineractIntegrationException.class)
                .hasMessageContaining("tính lại lãi");
        productMapper.validateReconciledProduct(objectMapper.readTree(json.replace("RECALC", "true")),
                "FINORA-FINERACT-V4", RepaymentMethod.ANNUITY);
    }

    @Test
    void shouldAcceptReconciledV2ProductOnlyWhenAdvancedAllocationIsIntact() throws Exception {
        JsonNode product = objectMapper.readTree("""
                {
                  "amortizationType": {"id": 1},
                  "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.progressive", "value": "Progressive"},
                  "paymentAllocation": [{
                    "transactionType": {"code": "DEFAULT", "value": "Default"},
                    "futureInstallmentAllocationRule": {"code": "REAMORTIZATION", "value": "Re-amortization"},
                    "paymentAllocationOrder": [{},{},{},{},{},{},{},{},{},{},{},{}]
                  }]
                }
                """);

        productMapper.validateReconciledProduct(product, "FINORA-FINERACT-V2", RepaymentMethod.ANNUITY);
    }

    @Test
    void shouldAcceptReconciledV3ProductOnlyWithNextInstallmentRule() throws Exception {
        JsonNode product = objectMapper.readTree("""
                {
                  "amortizationType": {"id": 1},
                  "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.progressive", "value": "Progressive"},
                  "paymentAllocation": [{
                    "transactionType": "DEFAULT",
                    "futureInstallmentAllocationRule": "NEXT_INSTALLMENT",
                    "paymentAllocationOrder": [{},{},{},{},{},{},{},{},{},{},{},{}]
                  }]
                }
                """);

        productMapper.validateReconciledProduct(product, "FINORA-FINERACT-V3", RepaymentMethod.ANNUITY);
    }

    @Test
    void shouldRejectReconciledV2ProductWithCumulativeSchedule() throws Exception {
        JsonNode product = objectMapper.readTree("""
                {
                  "amortizationType": {"id": 1},
                  "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.cumulative", "value": "Cumulative"},
                  "paymentAllocation": [{
                    "transactionType": "DEFAULT",
                    "futureInstallmentAllocationRule": "REAMORTIZATION",
                    "paymentAllocationOrder": [{},{},{},{},{},{},{},{},{},{},{},{}]
                  }]
                }
                """);

        assertThatThrownBy(() -> productMapper.validateReconciledProduct(
                product, "FINORA-FINERACT-V2", RepaymentMethod.ANNUITY))
                .isInstanceOf(FineractIntegrationException.class)
                .hasMessageContaining("PROGRESSIVE");
    }

    @Test
    void shouldRejectOldProgressiveProductForEqualPrincipalReconciliation() throws Exception {
        JsonNode oldProduct = objectMapper.readTree("""
                {
                  "amortizationType": {"id": 0},
                  "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.progressive", "value": "Progressive"},
                  "isInterestRecalculationEnabled": true
                }
                """);

        assertThatThrownBy(() -> productMapper.validateReconciledProduct(
                oldProduct, "FINORA-FINERACT-V4", RepaymentMethod.EQUAL_PRINCIPAL))
                .isInstanceOf(FineractIntegrationException.class)
                .hasMessageContaining("transaction strategy");
    }

    @Test
    void shouldAcceptCumulativeV4ProductForEqualPrincipalReconciliation() throws Exception {
        JsonNode product = objectMapper.readTree("""
                {
                  "amortizationType": {"id": 0},
                  "transactionProcessingStrategyCode": "mifos-standard-strategy",
                  "loanScheduleType": {"code": "loanScheduleType.cumulative", "value": "Cumulative"},
                  "isInterestRecalculationEnabled": true
                }
                """);

        productMapper.validateReconciledProduct(
                product, "FINORA-FINERACT-V4", RepaymentMethod.EQUAL_PRINCIPAL);
    }

    @Test
    void namespacedEnvironmentNeverReusesTheFineractNameOrShortNameOfAnotherEnvironment() {
        var e2e = new com.finora.loan.config.FineractProperties("http://fineract.test", "default", "e2e-user",
                "not-used", null, null, null, "FINORA-FINERACT-V4", 3, null, 10, null, "e2e");
        FineractProductPayloadMapper isolated = new FineractProductPayloadMapper(hashingService, repaymentPolicy, e2e);
        FineractProductConfiguration first = configuration(RepaymentMethod.ANNUITY);
        FineractProductConfiguration second = new FineractProductConfiguration(
                2L, 1L, "PERSONAL_STANDARD_2", "Vay tiêu dùng 2",
                new BigDecimal("10000000"), new BigDecimal("100000000"),
                6, 24, new BigDecimal("12.5000"), RepaymentMethod.ANNUITY,
                "FINORA-E2E-LP-2-V1", "FINORA-FINERACT-V4");

        var shared = productMapper.toCreateProductPayload(first);
        var isolatedFirst = isolated.toCreateProductPayload(first);
        var isolatedSecond = isolated.toCreateProductPayload(second);

        // Không gian tên rỗng: định danh cũ giữ nguyên để Product đã đồng bộ không đổi.
        assertThat(shared).containsEntry("name", "FINORA PERSONAL_STANDARD v1").containsEntry("shortName", "FW");
        // DB Loan khác dùng chung Fineract: tên và shortName khác hẳn, Product liên tiếp không trùng nhau.
        assertThat(isolatedFirst).containsEntry("name", "FINORA E2E PERSONAL_STANDARD v1");
        assertThat((String) isolatedFirst.get("shortName")).hasSizeBetween(2, 4).isNotEqualTo("FW")
                .isNotEqualTo(isolatedSecond.get("shortName"));
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
                RepaymentMethod.ANNUITY, "FINORA-FINERACT-V1",
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10));
    }

    private Map<String, Object> allocation(String rule, int order) {
        return Map.of("paymentAllocationRule", rule, "order", order);
    }
}
