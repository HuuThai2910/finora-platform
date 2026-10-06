package com.finora.payment.integration.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.payment.config.PaymentFineractProperties;
import com.finora.payment.domain.repayment.PaymentRepayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class FineractRepaymentGateway implements RepaymentCoreGateway {
    private static final DateTimeFormatter FINERACT_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    private final RestClient client;
    private final PaymentFineractProperties properties;

    public FineractRepaymentGateway(@Qualifier("paymentFineractRestClient") RestClient client,
            PaymentFineractProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public ScheduledRepaymentCoreQuote scheduledRepaymentQuote(Long fineractLoanId) {
        try {
            JsonNode template = get("/loans/%d/transactions/template?command=repayment".formatted(fineractLoanId));
            BigDecimal amount = money(template.path("total"), "amount");
            if (amount.signum() <= 0) {
                throw new FineractRepaymentException("FINERACT_NO_SCHEDULED_DUE",
                        "Khoản vay không có nghĩa vụ đến hạn để thanh toán", false, null);
            }
            JsonNode loan = get("/loans/%d".formatted(fineractLoanId));
            return new ScheduledRepaymentCoreQuote(amount, money(loan.path("summary"), "totalOverdue"));
        } catch (FineractRepaymentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new FineractRepaymentException("FINERACT_DUE_REJECTED",
                    "Fineract từ chối truy vấn khoản đến hạn với HTTP " + exception.getStatusCode().value(),
                    false, exception);
        } catch (ResourceAccessException exception) {
            throw new FineractRepaymentException("FINERACT_DUE_UNAVAILABLE",
                    "Chưa thể lấy số tiền đến hạn từ Fineract", true, exception);
        }
    }

    @Override
    public EarlySettlementCoreQuote earlySettlementQuote(Long fineractLoanId, LocalDate transactionDate) {
        try {
            JsonNode prepay = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/loans/{loanId}/transactions/template")
                            .queryParam("command", "prepayLoan")
                            .queryParam("dateFormat", "dd MMMM yyyy")
                            .queryParam("locale", "en")
                            .queryParam("transactionDate", FINERACT_DATE.format(transactionDate))
                            .build(fineractLoanId))
                    .headers(headers -> headers.setBasicAuth(properties.username(), properties.password()))
                    .retrieve().body(JsonNode.class);
            JsonNode loan = get("/loans/%d".formatted(fineractLoanId));
            BigDecimal amount = money(prepay, "amount");
            BigDecimal principal = money(prepay, "principalPortion");
            BigDecimal interest = money(prepay, "interestPortion");
            BigDecimal fee = money(prepay, "feeChargesPortion");
            BigDecimal penalty = money(prepay, "penaltyChargesPortion");
            if (amount.signum() <= 0 || principal.add(interest).add(fee).add(penalty).compareTo(amount) != 0) {
                throw new FineractRepaymentException("FINERACT_PREPAY_QUOTE_INVALID",
                        "Báo giá tất toán của Fineract không hợp lệ hoặc không cân", false, null);
            }
            LocalDate disbursed = date(loan.path("timeline").path("actualDisbursementDate"), "actualDisbursementDate");
            LocalDate maturity = date(loan.path("timeline").path("expectedMaturityDate"), "expectedMaturityDate");
            int termMonths = termMonths(loan, disbursed, maturity);
            return new EarlySettlementCoreQuote(transactionDate, amount, principal, interest, fee, penalty,
                    termMonths, disbursed, maturity);
        } catch (FineractRepaymentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new FineractRepaymentException("FINERACT_PREPAY_QUOTE_REJECTED",
                    "Fineract từ chối báo giá tất toán với HTTP " + exception.getStatusCode().value(), false, exception);
        } catch (ResourceAccessException exception) {
            throw new FineractRepaymentException("FINERACT_PREPAY_QUOTE_UNAVAILABLE",
                    "Chưa thể lấy báo giá tất toán từ Fineract", true, exception);
        } catch (RuntimeException exception) {
            throw new FineractRepaymentException("FINERACT_PREPAY_QUOTE_UNEXPECTED",
                    "Không đọc được báo giá tất toán từ Fineract", true, exception);
        }
    }

    @Override
    public PartialPrepaymentCoreSnapshot partialPrepaymentSnapshot(Long fineractLoanId,
            LocalDate transactionDate) {
        try {
            JsonNode due = get("/loans/%d/transactions/template?command=repayment".formatted(fineractLoanId));
            JsonNode payoff = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/loans/{loanId}/transactions/template")
                            .queryParam("command", "prepayLoan")
                            .queryParam("dateFormat", "dd MMMM yyyy")
                            .queryParam("locale", "en")
                            .queryParam("transactionDate", FINERACT_DATE.format(transactionDate))
                            .build(fineractLoanId))
                    .headers(headers -> headers.setBasicAuth(properties.username(), properties.password()))
                    .retrieve().body(JsonNode.class);
            JsonNode loan = get("/loans/%d?associations=repaymentSchedule".formatted(fineractLoanId));
            LocalDate disbursed = date(loan.path("timeline").path("actualDisbursementDate"),
                    "actualDisbursementDate");
            LocalDate maturity = date(loan.path("timeline").path("expectedMaturityDate"),
                    "expectedMaturityDate");
            NextDue nextDue = nextDue(loan.path("repaymentSchedule").path("periods"));
            return new PartialPrepaymentCoreSnapshot(transactionDate, money(due.path("total"), "amount"),
                    money(payoff, "amount"), money(loan.path("summary"), "principalOutstanding"),
                    nextDue.date(), nextDue.amount(), termMonths(loan, disbursed, maturity),
                    disbursed, maturity);
        } catch (FineractRepaymentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new FineractRepaymentException("FINERACT_PARTIAL_PREPAYMENT_QUOTE_REJECTED",
                    "Fineract từ chối dữ liệu báo giá trả trước một phần với HTTP "
                            + exception.getStatusCode().value(), false, exception);
        } catch (ResourceAccessException exception) {
            throw new FineractRepaymentException("FINERACT_PARTIAL_PREPAYMENT_QUOTE_UNAVAILABLE",
                    "Chưa thể lấy dữ liệu trả trước một phần từ Fineract", true, exception);
        }
    }

    @Override
    public PaymentRepayment.CoreBreakdown postRepayment(RepaymentCoreCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateFormat", "dd MMMM yyyy");
        body.put("locale", "en");
        body.put("transactionDate", FINERACT_DATE.format(command.transactionDate()));
        body.put("transactionAmount", command.amount());
        body.put("paymentTypeId", properties.repaymentPaymentTypeId());
        body.put("externalId", command.repaymentReference());
        body.put("note", "FINORA repayment " + command.repaymentReference());
        try {
            JsonNode created = client.post()
                    .uri("/loans/{loanId}/transactions?command=repayment", command.fineractLoanId())
                    .headers(headers -> headers.setBasicAuth(properties.username(), properties.password()))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            long transactionId = created == null ? 0 : created.path("resourceId").asLong();
            if (transactionId <= 0) throw new FineractRepaymentException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả resourceId cho repayment", true, null);
            JsonNode transaction = get("/loans/%d/transactions/%d"
                    .formatted(command.fineractLoanId(), transactionId));
            JsonNode loan = get("/loans/%d?associations=repaymentSchedule"
                    .formatted(command.fineractLoanId()));
            return map(transactionId, transaction, loan);
        } catch (FineractRepaymentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new FineractRepaymentException("FINERACT_REPAYMENT_REJECTED",
                    "Fineract từ chối repayment với HTTP " + exception.getStatusCode().value(), false, exception);
        } catch (ResourceAccessException exception) {
            throw new FineractRepaymentException("FINERACT_REPAYMENT_OUTCOME_UNKNOWN",
                    "Mất kết nối khi ghi repayment; phải đối soát trước khi thử lại", true, exception);
        } catch (RuntimeException exception) {
            throw new FineractRepaymentException("FINERACT_REPAYMENT_UNEXPECTED",
                    "Không hoàn tất được repayment trên Fineract", true, exception);
        }
    }

    @Override
    public Optional<PaymentRepayment.CoreBreakdown> findPostedRepayment(Long fineractLoanId,
            String repaymentReference) {
        try {
            JsonNode loan = get("/loans/%d?associations=transactions,repaymentSchedule".formatted(fineractLoanId));
            JsonNode transactions = loan.path("transactions");
            if (!transactions.isArray()) return Optional.empty();
            for (JsonNode transaction : transactions) {
                if (repaymentReference.equals(transaction.path("externalId").asText())
                        && !transaction.path("reversed").asBoolean(false)) {
                    long transactionId = transaction.path("id").asLong();
                    if (transactionId <= 0) throw new FineractRepaymentException("FINERACT_RESPONSE_INVALID",
                            "Transaction đối soát không có id", true, null);
                    JsonNode detail = get("/loans/%d/transactions/%d".formatted(fineractLoanId, transactionId));
                    return Optional.of(map(transactionId, detail, loan));
                }
            }
            return Optional.empty();
        } catch (FineractRepaymentException exception) {
            throw exception;
        } catch (RestClientResponseException | ResourceAccessException exception) {
            throw new FineractRepaymentException("FINERACT_RECONCILIATION_UNAVAILABLE",
                    "Chưa thể đọc Fineract để đối soát repayment", true, exception);
        } catch (RuntimeException exception) {
            throw new FineractRepaymentException("FINERACT_RECONCILIATION_UNEXPECTED",
                    "Không hoàn tất được đối soát repayment", true, exception);
        }
    }

    private JsonNode get(String uri) {
        return client.get().uri(uri).headers(headers -> headers.setBasicAuth(properties.username(), properties.password()))
                .retrieve().body(JsonNode.class);
    }

    private PaymentRepayment.CoreBreakdown map(long transactionId, JsonNode transaction, JsonNode loan) {
        BigDecimal principal = money(transaction, "principalPortion");
        BigDecimal interest = money(transaction, "interestPortion");
        BigDecimal fee = money(transaction, "feeChargesPortion");
        BigDecimal penalty = money(transaction, "penaltyChargesPortion");
        JsonNode summary = loan.path("summary");
        BigDecimal outstanding = money(summary, "principalOutstanding");
        BigDecimal outstandingInterest = money(summary, "interestOutstanding");
        BigDecimal outstandingFee = money(summary, "feeChargesOutstanding");
        BigDecimal outstandingPenalty = money(summary, "penaltyChargesOutstanding");
        BigDecimal totalOutstanding = money(summary, "totalOutstanding");
        BigDecimal overdueAmount = money(summary, "totalOverdue");
        NextDue nextDue = nextDue(loan.path("repaymentSchedule").path("periods"));
        return new PaymentRepayment.CoreBreakdown(transactionId, principal, interest, fee, penalty,
                outstanding, outstandingInterest, outstandingFee, outstandingPenalty,
                totalOutstanding, overdueAmount, nextDue.date(), nextDue.amount());
    }

    private NextDue nextDue(JsonNode periods) {
        if (periods.isArray()) {
            for (JsonNode period : periods) {
                BigDecimal outstanding = money(period, "totalOutstandingForPeriod");
                if (outstanding.signum() > 0 && period.path("dueDate").isArray()) {
                    JsonNode date = period.path("dueDate");
                    return new NextDue(LocalDate.of(date.get(0).asInt(), date.get(1).asInt(), date.get(2).asInt()), outstanding);
                }
            }
        }
        return new NextDue(null, BigDecimal.ZERO.setScale(2));
    }

    private BigDecimal money(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return BigDecimal.ZERO.setScale(2);
        return value.decimalValue().setScale(2);
    }

    private LocalDate date(JsonNode value, String field) {
        if (!value.isArray() || value.size() < 3) {
            throw new FineractRepaymentException("FINERACT_RESPONSE_INVALID",
                    "Fineract thiếu " + field + " của khoản vay", false, null);
        }
        return LocalDate.of(value.get(0).asInt(), value.get(1).asInt(), value.get(2).asInt());
    }

    private int termMonths(JsonNode loan, LocalDate disbursed, LocalDate maturity) {
        int value = loan.path("termFrequency").asInt(0);
        int unit = loan.path("termPeriodFrequencyType").path("id").asInt(-1);
        if (value > 0) {
            if (unit == 2) return value;
            if (unit == 3) return Math.multiplyExact(value, 12);
        }
        long months = ChronoUnit.MONTHS.between(disbursed, maturity);
        if (disbursed.plusMonths(months).isBefore(maturity)) months++;
        if (months <= 0 || months > Integer.MAX_VALUE) {
            throw new FineractRepaymentException("FINERACT_RESPONSE_INVALID",
                    "Không xác định được thời hạn khoản vay", false, null);
        }
        return (int) months;
    }

    private record NextDue(LocalDate date, BigDecimal amount) {}
}
