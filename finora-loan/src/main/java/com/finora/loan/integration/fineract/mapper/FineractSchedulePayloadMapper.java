package com.finora.loan.integration.fineract.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.fineract.contract.SchedulePeriod;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Chỉ ánh xạ request/response của use case tính lịch trả dự kiến. */
@Component
@RequiredArgsConstructor
public class FineractSchedulePayloadMapper {

    private static final String POLICY_VERSION = "FINERACT_1_15_SCHEDULE_V1";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final HashingService hashingService;
    private final FineractRepaymentPolicy repaymentPolicy;

    /** Ánh xạ đúng amount, term, rate và repayment method đã được Loan kiểm tra. */
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
        payload.put("amortizationType", repaymentPolicy.amortizationType(source.repaymentMethod()));
        payload.put("interestType", repaymentPolicy.interestType());
        payload.put("interestCalculationPeriodType", repaymentPolicy.interestCalculationPeriodType());
        payload.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        payload.put("expectedDisbursementDate", source.expectedDisbursementDate());
        payload.put("submittedOnDate", source.submittedOnDate());
        payload.put("dateFormat", "yyyy-MM-dd");
        payload.put("locale", "en");
        return payload;
    }

    /**
     * Chỉ nhận allowlist field cần cho nghiệp vụ. Field tiền/ngày bắt buộc bị thiếu phải
     * làm hỏng contract thay vì âm thầm biến thành 0 rồi đưa sang AI.
     */
    public ScheduleCalculationResult toScheduleResult(
            ScheduleCalculationRequest request,
            long fineractClientId,
            JsonNode response
    ) {
        if (response == null || response.isNull()) {
            throw contractMismatch("Fineract trả response lịch rỗng");
        }
        JsonNode schedule = response.has("loanSchedule") ? response.path("loanSchedule") : response;
        JsonNode responsePeriods = schedule.path("periods");
        if (!responsePeriods.isArray()) {
            throw contractMismatch("Fineract không trả mảng periods hợp lệ");
        }
        List<SchedulePeriod> periods = new ArrayList<>();
        for (JsonNode period : responsePeriods) {
            int periodNumber = period.path("period").asInt(0);
            if (periodNumber <= 0) {
                continue;
            }
            periods.add(new SchedulePeriod(
                    periodNumber,
                    requiredDate(period.path("fromDate"), "periods.fromDate"),
                    requiredDate(period.path("dueDate"), "periods.dueDate"),
                    period.path("daysInPeriod").isNumber() ? period.path("daysInPeriod").asInt() : null,
                    requiredMoney(period, "principalDue", "principalOriginalDue"),
                    requiredMoney(period, "interestDue", "interestOriginalDue"),
                    optionalMoney(period, "feeChargesDue", "feeChargesDue"),
                    optionalMoney(period, "penaltyChargesDue", "penaltyChargesDue"),
                    requiredMoney(period, "totalDueForPeriod", "totalOriginalDueForPeriod"),
                    requiredMoney(period, "principalLoanBalanceOutstanding", "principalLoanBalanceOutstanding")
            ));
        }
        if (periods.isEmpty()) {
            throw contractMismatch("Fineract không trả kỳ thanh toán hợp lệ");
        }

        BigDecimal totalPrincipal = requiredMoney(schedule, "totalPrincipalExpected", "totalPrincipalDisbursed");
        BigDecimal totalInterest = requiredMoney(schedule, "totalInterestCharged", "totalInterestCharged");
        BigDecimal totalFees = optionalMoney(schedule, "totalFeeChargesCharged", "totalFeeChargesCharged");
        BigDecimal totalPenalties = optionalMoney(
                schedule, "totalPenaltyChargesCharged", "totalPenaltyChargesCharged");
        BigDecimal totalRepayment = requiredMoney(schedule, "totalRepaymentExpected", "totalRepaymentExpected");
        BigDecimal firstInstallment = periods.getFirst().totalDue();
        BigDecimal maximumInstallment = periods.stream()
                .map(SchedulePeriod::totalDue)
                .max(BigDecimal::compareTo)
                .orElse(firstInstallment);
        String requestJson = hashingService.toJson(toSchedulePayload(request, fineractClientId));
        String periodsJson = hashingService.toJson(periods);
        Map<String, Object> responseAllowlist = Map.of(
                "totalPrincipal", totalPrincipal,
                "totalInterest", totalInterest,
                "totalFees", totalFees,
                "totalPenalties", totalPenalties,
                "totalRepayment", totalRepayment,
                "periods", periods
        );
        return new ScheduleCalculationResult(
                normalizeMoney(request.amount(), "request.amount"),
                request.termMonths(),
                request.annualInterestRate().setScale(4, RoundingMode.HALF_UP),
                request.repaymentMethod(),
                request.expectedDisbursementDate(),
                firstInstallment,
                maximumInstallment,
                totalPrincipal,
                totalInterest,
                totalFees,
                totalPenalties,
                totalRepayment,
                List.copyOf(periods),
                requestJson,
                periodsJson,
                POLICY_VERSION,
                hashingService.sha256(responseAllowlist)
        );
    }

    /** Tìm đúng Client kỹ thuật theo external ID; không nhận bản ghi gần giống. */
    public Optional<Long> findClientIdByExternalId(JsonNode response, String externalId) {
        JsonNode clients = response == null ? null : response.path("pageItems");
        if (clients == null || !clients.isArray()) {
            throw contractMismatch("Fineract không trả danh sách Client hợp lệ khi tính preview");
        }
        for (JsonNode client : clients) {
            if (externalId.equals(client.path("externalId").asText())) {
                long clientId = client.path("id").asLong(0L);
                if (clientId <= 0) {
                    throw contractMismatch("Fineract trả Client preview nhưng thiếu ID hợp lệ");
                }
                return Optional.of(clientId);
            }
        }
        return Optional.empty();
    }

    private BigDecimal requiredMoney(JsonNode node, String preferred, String fallback) {
        JsonNode value = moneyNode(node, preferred, fallback);
        if (value == null) {
            throw contractMismatch("Fineract thiếu trường tiền bắt buộc: " + preferred);
        }
        return parseMoney(value, preferred);
    }

    private BigDecimal optionalMoney(JsonNode node, String preferred, String fallback) {
        JsonNode value = moneyNode(node, preferred, fallback);
        return value == null ? ZERO : parseMoney(value, preferred);
    }

    private JsonNode moneyNode(JsonNode node, String preferred, String fallback) {
        JsonNode value = node.path(preferred);
        if (!value.isNumber() && !value.isTextual()) {
            value = node.path(fallback);
        }
        return value.isNumber() || value.isTextual() ? value : null;
    }

    private BigDecimal parseMoney(JsonNode value, String field) {
        if (value.isNumber()) {
            return normalizeMoney(value.decimalValue(), field);
        }
        try {
            return normalizeMoney(new BigDecimal(value.asText()), field);
        } catch (NumberFormatException exception) {
            throw contractMismatch("Fineract trả trường tiền không hợp lệ: " + field);
        }
    }

    private BigDecimal normalizeMoney(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw contractMismatch("Fineract trả trường tiền không hợp lệ: " + field);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate requiredDate(JsonNode node, String field) {
        try {
            if (node.isArray() && node.size() >= 3) {
                return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
            }
            if (node.isTextual()) {
                return LocalDate.parse(node.asText());
            }
        } catch (DateTimeException exception) {
            throw contractMismatch("Fineract trả ngày không hợp lệ: " + field);
        }
        throw contractMismatch("Fineract thiếu ngày bắt buộc: " + field);
    }

    private FineractIntegrationException contractMismatch(String message) {
        return new FineractIntegrationException("FINERACT_CONTRACT_MISMATCH", message, false, null);
    }
}
