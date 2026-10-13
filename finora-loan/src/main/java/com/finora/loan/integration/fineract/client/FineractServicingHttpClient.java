package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.servicing.LoanServicingHistory;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public LoanServicingHistory readHistory(Long fineractLoanId) {
        JsonNode loan = executor.execute(FineractCallGroup.SERVICING, "read-servicing-history", () ->
                client.get().uri("/loans/{id}?associations=repaymentSchedule,transactions", fineractLoanId)
                        .headers(executor::authenticate).retrieve().body(JsonNode.class));
        if (loan == null || !loan.isObject()) {
            throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả dữ liệu lịch sử khoản vay", false, null);
        }
        return new LoanServicingHistory(requiredText(loan, "externalId"), principalMovements(loan.path("transactions")),
                installments(loan.path("repaymentSchedule").path("periods")));
    }

    /**
     * Giải ngân cộng số tiền giải ngân; giao dịch khác (trả nợ, xóa nợ, hoàn tiền...) trừ phần gốc của nó.
     * Bỏ giao dịch đã đảo và giao dịch dồn tích lãi (accrual không động tới gốc).
     */
    private List<LoanServicingHistory.PrincipalMovement> principalMovements(JsonNode transactions) {
        List<LoanServicingHistory.PrincipalMovement> movements = new ArrayList<>();
        if (!transactions.isArray()) return movements;
        for (JsonNode transaction : transactions) {
            LocalDate date = optionalDate(transaction.path("date"));
            JsonNode type = transaction.path("type");
            if (date == null || transaction.path("manuallyReversed").asBoolean(false)
                    || type.path("accrual").asBoolean(false)) {
                continue;
            }
            BigDecimal amount = type.path("disbursement").asBoolean(false)
                    ? money(transaction, "amount")
                    : money(transaction, "principalPortion").negate();
            if (amount.signum() != 0) {
                movements.add(new LoanServicingHistory.PrincipalMovement(date, amount));
            }
        }
        return movements;
    }

    /** Dòng đầu của lịch là dòng giải ngân (không có số kỳ), không phải một kỳ phải trả. */
    private List<LoanServicingHistory.Installment> installments(JsonNode periods) {
        List<LoanServicingHistory.Installment> installments = new ArrayList<>();
        if (!periods.isArray()) return installments;
        for (JsonNode period : periods) {
            LocalDate due = optionalDate(period.path("dueDate"));
            if (!period.hasNonNull("period") || due == null) continue;
            installments.add(new LoanServicingHistory.Installment(due,
                    optionalDate(period.path("obligationsMetOnDate"))));
        }
        return installments;
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
