package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FineractServicingHttpClient implements FineractServicingGateway {
    private final RestClient client;
    private final FineractRequestExecutor executor;

    public FineractServicingHttpClient(RestClient fineractRestClient, FineractRequestExecutor executor) {
        this.client = fineractRestClient;
        this.executor = executor;
    }

    @Override
    public LoanServicingSnapshot read(Long fineractLoanId, LocalDate businessDate) {
        JsonNode loan = executor.execute(FineractCallGroup.SERVICING, "read-servicing-loan", () ->
                client.get().uri("/loans/{id}?associations=repaymentSchedule", fineractLoanId)
                        .headers(executor::authenticate).retrieve().body(JsonNode.class));
        if (loan == null || !loan.isObject()) {
            throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả dữ liệu servicing", false, null);
        }
        JsonNode summary = loan.path("summary");
        ScheduleDates dates = scheduleDates(loan.path("repaymentSchedule").path("periods"), businessDate);
        BigDecimal overdue = money(summary, "totalOverdue");
        LocalDate overdueSince = overdue.signum() == 0 ? null : dates.overdueSince();
        int dpd = overdueSince == null ? 0 : Math.toIntExact(ChronoUnit.DAYS.between(overdueSince, businessDate));
        return new LoanServicingSnapshot(positiveId(loan, "id"), requiredText(loan, "externalId"),
                status(loan), money(summary, "principalDisbursed"),
                money(summary, "principalPaid"), money(summary, "principalOutstanding"),
                money(summary, "interestCharged"), money(summary, "interestPaid"),
                money(summary, "interestOutstanding"), money(summary, "feeChargesOutstanding"),
                money(summary, "penaltyChargesOutstanding"), money(summary, "totalOutstanding"), overdue,
                overdueSince, Math.max(0, dpd), dates.nextDueDate(), dates.nextDueAmount(),
                optionalDate(loan.path("timeline").path("expectedMaturityDate")));
    }

    private Long positiveId(JsonNode node, String field) {
        long value = node.path(field).asLong();
        if (value <= 0) {
            throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả định danh khoản vay hợp lệ", false, null);
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả externalId khoản vay", false, null);
        }
        return value;
    }

    private ScheduleDates scheduleDates(JsonNode periods, LocalDate businessDate) {
        LocalDate overdueSince = null;
        LocalDate nextDue = null;
        BigDecimal nextAmount = null;
        if (periods.isArray()) {
            for (JsonNode period : periods) {
                BigDecimal outstanding = money(period, "totalOutstandingForPeriod");
                LocalDate due = optionalDate(period.path("dueDate"));
                if (outstanding.signum() <= 0 || due == null) continue;
                if (nextDue == null || due.isBefore(nextDue)) {
                    nextDue = due;
                    nextAmount = outstanding;
                }
                if (due.isBefore(businessDate) && (overdueSince == null || due.isBefore(overdueSince))) {
                    overdueSince = due;
                }
            }
        }
        return new ScheduleDates(overdueSince, nextDue, nextAmount);
    }

    private String status(JsonNode loan) {
        String code = loan.path("status").path("code").asText();
        if (!code.isBlank()) return code;
        String value = loan.path("status").path("value").asText();
        return value.isBlank() ? "UNKNOWN" : value;
    }

    private BigDecimal money(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? BigDecimal.ZERO.setScale(2) : value.decimalValue().setScale(2);
    }

    private LocalDate optionalDate(JsonNode value) {
        if (!value.isArray() || value.size() < 3) return null;
        return LocalDate.of(value.get(0).asInt(), value.get(1).asInt(), value.get(2).asInt());
    }

    private record ScheduleDates(LocalDate overdueSince, LocalDate nextDueDate, BigDecimal nextDueAmount) {}
}
