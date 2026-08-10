package com.finora.loan.integration.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.RepaymentMethod;
import com.finora.loan.service.HashingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FineractPayloadMapper {

    private static final String POLICY_VERSION = "FINERACT_1_15_SCHEDULE_V1";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final HashingService hashingService;

    public FineractPayloadMapper(HashingService hashingService) {
        this.hashingService = hashingService;
    }

    /**
     * Ánh xạ Product FINORA sang allowlist Fineract 1.15; ID kế toán không được hard-code khi accountingRule=1.
     */
    public Map<String, Object> toCreateProductPayload(FineractProductConfiguration source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", fineractName(source));
        payload.put("shortName", shortName(source.loanProductId(), source.finoraProductVersion()));
        payload.put("description", "FINORA product " + source.code() + " / " + source.externalId());
        payload.put("externalId", source.externalId());
        payload.put("currencyCode", "VND");
        payload.put("digitsAfterDecimal", 0);
        payload.put("inMultiplesOf", 1000);
        payload.put("principal", source.minAmount().add(source.maxAmount()).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP));
        payload.put("minPrincipal", source.minAmount());
        payload.put("maxPrincipal", source.maxAmount());
        payload.put("numberOfRepayments", source.minTermMonths());
        payload.put("minNumberOfRepayments", source.minTermMonths());
        payload.put("maxNumberOfRepayments", source.maxTermMonths());
        payload.put("repaymentEvery", 1);
        payload.put("repaymentFrequencyType", 2);
        payload.put("interestRatePerPeriod", source.annualInterestRate());
        payload.put("interestRateFrequencyType", 3);
        payload.put("amortizationType", amortizationType(source.repaymentMethod()));
        payload.put("interestType", 0);
        payload.put("interestCalculationPeriodType", 1);
        payload.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        payload.put("accountingRule", 1);
        payload.put("isInterestRecalculationEnabled", false);
        payload.put("daysInYearType", 1);
        payload.put("daysInMonthType", 1);
        payload.put("dateFormat", "yyyy-MM-dd");
        payload.put("locale", "en");
        return payload;
    }

    public Map<String, Object> toSchedulePayload(ScheduleCalculationRequest source, long fineractClientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", fineractClientId);
        payload.put("productId", source.fineractProductId());
        payload.put("loanType", "individual");
        payload.put("principal", source.amount());
        payload.put("loanTermFrequency", source.termMonths());
        payload.put("loanTermFrequencyType", 2);
        payload.put("numberOfRepayments", source.termMonths());
        payload.put("repaymentEvery", 1);
        payload.put("repaymentFrequencyType", 2);
        payload.put("interestRatePerPeriod", source.annualInterestRate());
        payload.put("amortizationType", amortizationType(source.repaymentMethod()));
        payload.put("interestType", 0);
        payload.put("interestCalculationPeriodType", 1);
        payload.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        payload.put("expectedDisbursementDate", source.expectedDisbursementDate());
        payload.put("submittedOnDate", source.submittedOnDate());
        payload.put("dateFormat", "yyyy-MM-dd");
        payload.put("locale", "en");
        return payload;
    }

    /** Chỉ lấy những field lịch trả mà FINORA thực sự sử dụng, không đưa raw response vào domain. */
    public ScheduleCalculationResult toScheduleResult(
            ScheduleCalculationRequest request,
            long fineractClientId,
            JsonNode response
    ) {
        JsonNode schedule = response.has("loanSchedule") ? response.path("loanSchedule") : response;
        List<SchedulePeriod> periods = new ArrayList<>();
        for (JsonNode period : schedule.path("periods")) {
            int periodNumber = period.path("period").asInt(0);
            if (periodNumber <= 0) {
                continue;
            }
            periods.add(new SchedulePeriod(
                    periodNumber,
                    date(period.path("fromDate")),
                    date(period.path("dueDate")),
                    period.path("daysInPeriod").isNumber() ? period.path("daysInPeriod").asInt() : null,
                    money(period, "principalDue", "principalOriginalDue"),
                    money(period, "interestDue", "interestOriginalDue"),
                    money(period, "feeChargesDue", "feeChargesDue"),
                    money(period, "penaltyChargesDue", "penaltyChargesDue"),
                    money(period, "totalDueForPeriod", "totalOriginalDueForPeriod"),
                    money(period, "principalLoanBalanceOutstanding", "principalLoanBalanceOutstanding")
            ));
        }
        if (periods.isEmpty()) {
            throw new FineractIntegrationException(
                    "FINERACT_CONTRACT_MISMATCH",
                    "Fineract không trả kỳ thanh toán hợp lệ",
                    false,
                    null
            );
        }
        BigDecimal firstInstallment = periods.getFirst().totalDue();
        BigDecimal maximumInstallment = periods.stream()
                .map(SchedulePeriod::totalDue)
                .max(BigDecimal::compareTo)
                .orElse(firstInstallment);
        String requestJson = hashingService.toJson(toSchedulePayload(request, fineractClientId));
        String periodsJson = hashingService.toJson(periods);
        String responseAllowlist = hashingService.toJson(Map.of(
                "totalPrincipal", money(schedule, "totalPrincipalExpected", "totalPrincipalDisbursed"),
                "totalInterest", money(schedule, "totalInterestCharged", "totalInterestCharged"),
                "totalFees", money(schedule, "totalFeeChargesCharged", "totalFeeChargesCharged"),
                "totalPenalties", money(schedule, "totalPenaltyChargesCharged", "totalPenaltyChargesCharged"),
                "totalRepayment", money(schedule, "totalRepaymentExpected", "totalRepaymentExpected"),
                "periods", periods
        ));
        return new ScheduleCalculationResult(
                money(request.amount()),
                request.termMonths(),
                request.annualInterestRate().setScale(4, RoundingMode.HALF_UP),
                request.repaymentMethod(),
                request.expectedDisbursementDate(),
                firstInstallment,
                maximumInstallment,
                money(schedule, "totalPrincipalExpected", "totalPrincipalDisbursed"),
                money(schedule, "totalInterestCharged", "totalInterestCharged"),
                money(schedule, "totalFeeChargesCharged", "totalFeeChargesCharged"),
                money(schedule, "totalPenaltyChargesCharged", "totalPenaltyChargesCharged"),
                money(schedule, "totalRepaymentExpected", "totalRepaymentExpected"),
                List.copyOf(periods),
                requestJson,
                periodsJson,
                POLICY_VERSION,
                hashingService.sha256Text(responseAllowlist)
        );
    }

    public String sanitizedProductResponse(JsonNode response) {
        return hashingService.toJson(Map.of("resourceId", response.path("resourceId").asLong()));
    }

    /**
     * Đối chiếu chính xác external ID; không dùng contains/prefix vì có thể gắn nhầm mapping sang Product khác.
     */
    public Optional<FineractProductCreationResult> findProductByExternalId(JsonNode products, String externalId) {
        if (products == null || !products.isArray()) {
            throw new FineractIntegrationException(
                    "FINERACT_CONTRACT_MISMATCH",
                    "Fineract không trả danh sách Product hợp lệ khi đối chiếu",
                    false,
                    null
            );
        }
        for (JsonNode product : products) {
            if (!externalId.equals(product.path("externalId").asText())) {
                continue;
            }
            long resourceId = product.path("id").asLong(0L);
            if (resourceId <= 0) {
                throw new FineractIntegrationException(
                        "FINERACT_CONTRACT_MISMATCH",
                        "Fineract trả Product đối chiếu nhưng thiếu ID hợp lệ",
                        false,
                        null
                );
            }
            String snapshot = hashingService.toJson(Map.of(
                    "resourceId", resourceId,
                    "externalId", externalId,
                    "reconciled", true
            ));
            return Optional.of(new FineractProductCreationResult(resourceId, snapshot));
        }
        return Optional.empty();
    }

    /** Tìm đúng Client kỹ thuật theo external ID; không nhận phần tử gần giống hoặc thiếu ID. */
    public Optional<Long> findClientIdByExternalId(JsonNode response, String externalId) {
        JsonNode clients = response == null ? null : response.path("pageItems");
        if (clients == null || !clients.isArray()) {
            throw new FineractIntegrationException(
                    "FINERACT_CONTRACT_MISMATCH",
                    "Fineract không trả danh sách Client hợp lệ khi tính preview",
                    false,
                    null
            );
        }
        for (JsonNode client : clients) {
            if (externalId.equals(client.path("externalId").asText())) {
                long clientId = client.path("id").asLong(0L);
                if (clientId <= 0) {
                    throw new FineractIntegrationException(
                            "FINERACT_CONTRACT_MISMATCH",
                            "Fineract trả Client preview nhưng thiếu ID hợp lệ",
                            false,
                            null
                    );
                }
                return Optional.of(clientId);
            }
        }
        return Optional.empty();
    }

    private String fineractName(FineractProductConfiguration source) {
        String name = "FINORA " + source.code() + " v" + source.finoraProductVersion();
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private String shortName(Long productId, Long productVersion) {
        long value = Math.abs((productId * 31L) + productVersion);
        String code = Long.toString(value, 36).toUpperCase();
        return ("F" + code).substring(0, Math.min(4, code.length() + 1));
    }

    private int amortizationType(RepaymentMethod repaymentMethod) {
        return repaymentMethod == RepaymentMethod.ANNUITY ? 1 : 0;
    }

    private BigDecimal money(JsonNode node, String preferred, String fallback) {
        JsonNode value = node.path(preferred);
        if (!value.isNumber() && !value.isTextual()) {
            value = node.path(fallback);
        }
        if (value.isNumber()) {
            return money(value.decimalValue());
        }
        if (value.isTextual()) {
            try {
                return money(new BigDecimal(value.asText()));
            } catch (NumberFormatException ignored) {
                return ZERO;
            }
        }
        return ZERO;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate date(JsonNode node) {
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        if (node.isTextual()) {
            return LocalDate.parse(node.asText());
        }
        return null;
    }
}
