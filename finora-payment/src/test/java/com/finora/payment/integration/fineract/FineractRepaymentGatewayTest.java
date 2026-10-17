package com.finora.payment.integration.fineract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finora.payment.config.PaymentFineractProperties;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractRepaymentGatewayTest {

    @Test
    void readsScheduledAmountFromTopLevelFieldOfFineractRepaymentTemplate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template?command=repayment"))
                .andRespond(withSuccess(repaymentTemplateJson("2680000.000000"), MediaType.APPLICATION_JSON));
        // Kỳ 1 đã trả hết, kỳ 2 còn nợ: ngày đến hạn của khoản đang thu là ngày của kỳ 2 (D16).
        server.expect(requestTo("http://fineract.test/loans/77?associations=repaymentSchedule"))
                .andRespond(withSuccess("""
                        {"summary":{"totalOverdue":250000.00},
                         "repaymentSchedule":{"periods":[
                           {"dueDate":[2026,10,17]},
                           {"period":1,"dueDate":[2026,11,17],"totalOutstandingForPeriod":0},
                           {"period":2,"dueDate":[2026,12,17],"totalOutstandingForPeriod":2680000.000000}]}}
                        """, MediaType.APPLICATION_JSON));

        ScheduledRepaymentCoreQuote quote = gateway.scheduledRepaymentQuote(77L);
        assertThat(quote.amount()).isEqualByComparingTo("2680000.00");
        assertThat(quote.overdueAmount()).isEqualByComparingTo("250000.00");
        assertThat(quote.nextDueDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 17));
        server.verify();
    }

    @Test
    void rejectsRepaymentTemplateWithoutScheduledDueBeforeReadingLoan() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template?command=repayment"))
                .andRespond(withSuccess(repaymentTemplateJson("0"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.scheduledRepaymentQuote(77L))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_NO_SCHEDULED_DUE");
                    assertThat(exception.outcomeUnknown()).isFalse();
                });
        server.verify();
    }

    @Test
    void partialPrepaymentSnapshotReadsScheduledDueFromRepaymentTemplate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template?command=repayment"))
                .andRespond(withSuccess(repaymentTemplateJson("2000000.000000"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template"
                        + "?command=prepayLoan&dateFormat=dd%20MMMM%20yyyy&locale=en"
                        + "&transactionDate=03%20October%202026"))
                .andRespond(withSuccess("{\"amount\":20100000.00}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77?associations=repaymentSchedule"))
                .andRespond(withSuccess("""
                        {"termFrequency":12,"termPeriodFrequencyType":{"id":2},
                         "timeline":{"actualDisbursementDate":[2026,9,1],
                                     "expectedMaturityDate":[2027,9,1]},
                         "summary":{"principalOutstanding":20000000.00},
                         "repaymentSchedule":{"periods":[
                           {"dueDate":[2026,10,1],"totalOutstandingForPeriod":2000000.00}]}}
                        """, MediaType.APPLICATION_JSON));

        PartialPrepaymentCoreSnapshot snapshot =
                gateway.partialPrepaymentSnapshot(77L, LocalDate.of(2026, 10, 3));

        assertThat(snapshot.scheduledDue()).isEqualByComparingTo("2000000.00");
        assertThat(snapshot.payoffAmount()).isEqualByComparingTo("20100000.00");
        assertThat(snapshot.outstandingPrincipal()).isEqualByComparingTo("20000000.00");
        assertThat(snapshot.nextDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(snapshot.originalTermMonths()).isEqualTo(12);
        server.verify();
    }

    @Test
    void reconciliationIgnoresManuallyReversedTransaction() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77?associations=transactions,repaymentSchedule"))
                .andRespond(withSuccess(loanJson().replace("\"manuallyReversed\": false",
                        "\"manuallyReversed\": true"), MediaType.APPLICATION_JSON));

        assertThat(gateway.findPostedRepayment(77L, "FINORA-REPAY-abc")).isEmpty();
        server.verify();
    }

    @Test
    void reconciliationFindsExistingTransactionWithoutPostingAgain() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77?associations=transactions,repaymentSchedule"))
                .andRespond(withSuccess(loanJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77/transactions/501"))
                .andRespond(withSuccess(transactionJson(), MediaType.APPLICATION_JSON));

        var result = gateway.findPostedRepayment(77L, "FINORA-REPAY-abc");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().transactionId()).isEqualTo(501L);
        assertThat(result.orElseThrow().totalOutstanding()).isEqualByComparingTo("10100.00");
        server.verify();
    }

    @Test
    void reconciliationReturnsEmptyWhenReferenceWasNotRecorded() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77?associations=transactions,repaymentSchedule"))
                .andRespond(withSuccess(loanJson(), MediaType.APPLICATION_JSON));

        assertThat(gateway.findPostedRepayment(77L, "missing")).isEmpty();
        server.verify();
    }

    @Test
    void readsEarlySettlementQuoteFromReadOnlyPrepayTemplateAndLoanTerms() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template"
                        + "?command=prepayLoan&dateFormat=dd%20MMMM%20yyyy&locale=en"
                        + "&transactionDate=03%20October%202026"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"amount":10100000.00,"principalPortion":10000000.00,
                         "interestPortion":100000.00,"feeChargesPortion":0.00,
                         "penaltyChargesPortion":0.00}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77"))
                .andRespond(withSuccess("""
                        {"termFrequency":24,"termPeriodFrequencyType":{"id":2},
                         "timeline":{"actualDisbursementDate":[2026,10,1],
                                     "expectedMaturityDate":[2028,10,1]}}
                        """, MediaType.APPLICATION_JSON));

        EarlySettlementCoreQuote quote = gateway.earlySettlementQuote(77L, LocalDate.of(2026, 10, 3));

        assertThat(quote.amount()).isEqualByComparingTo("10100000.00");
        assertThat(quote.principal()).isEqualByComparingTo("10000000.00");
        assertThat(quote.originalTermMonths()).isEqualTo(24);
        assertThat(quote.disbursedDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        server.verify();
    }

    @Test
    void businessRejectionOfRepaymentPostIsDefinitiveAndAllowsRefund() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://fineract.test/loans/77/transactions?command=repayment"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> gateway(builder).postRepayment(repaymentCommand()))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_REJECTED");
                    assertThat(exception.outcomeUnknown()).isFalse();
                });
        server.verify();
    }

    @Test
    void serverErrorOnRepaymentPostIsUnknownAndMustBeReconciled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://fineract.test/loans/77/transactions?command=repayment"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        // 5xx không chứng minh Fineract đã rollback: không được hoàn tiền, phải đối soát theo externalId.
        assertThatThrownBy(() -> gateway(builder).postRepayment(repaymentCommand()))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_OUTCOME_UNKNOWN");
                    assertThat(exception.outcomeUnknown()).isTrue();
                });
        server.verify();
    }

    @Test
    void readFailureAfterSuccessfulPostNeverLooksLikeRejection() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://fineract.test/loans/77/transactions?command=repayment"))
                .andRespond(withSuccess("{\"resourceId\":501}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77/transactions/501"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Core đã ghi repayment (có resourceId); hoàn tiền lúc này sẽ làm ledger lệch Fineract.
        assertThatThrownBy(() -> gateway(builder).postRepayment(repaymentCommand()))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_POSTED_READ_FAILED");
                    assertThat(exception.outcomeUnknown()).isTrue();
                });
        server.verify();
    }

    private RepaymentCoreCommand repaymentCommand() {
        return new RepaymentCoreCommand(77L, "FINORA-REPAY-abc", LocalDate.of(2026, 10, 17), new java.math.BigDecimal("1000.00"));
    }

    private FineractRepaymentGateway gateway(RestClient.Builder builder) {
        PaymentFineractProperties properties = new PaymentFineractProperties(
                "http://fineract.test", "default", "mifos", "password", 1L,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new FineractRepaymentGateway(builder.build(), properties);
    }

    /**
     * Shape thật của {@code GET /loans/{id}/transactions/template?command=repayment} trên Fineract 1.15.0
     * (đọc từ fixture local ngày 2026-10-17, bỏ danh sách payment type). Tiền nằm ở field cấp gốc.
     */
    private String repaymentTemplateJson(String amount) {
        return """
                {
                  "type": {"id": 2, "code": "loanTransactionType.repayment", "value": "Repayment"},
                  "date": [2026, 11, 6],
                  "currency": {"code": "VND", "decimalPlaces": 0},
                  "amount": %s,
                  "netDisbursalAmount": 30000000.000000,
                  "principalPortion": 2355000.000000,
                  "interestPortion": 325000.000000,
                  "feeChargesPortion": 0,
                  "penaltyChargesPortion": 0,
                  "manuallyReversed": false
                }
                """.formatted(amount);
    }

    private String loanJson() {
        return """
                {
                  "transactions": [{"id": 501, "externalId": "FINORA-REPAY-abc", "manuallyReversed": false}],
                  "summary": {
                    "principalOutstanding": 9200.00,
                    "interestOutstanding": 900.00,
                    "feeChargesOutstanding": 0.00,
                    "penaltyChargesOutstanding": 0.00,
                    "totalOutstanding": 10100.00,
                    "totalOverdue": 0.00
                  },
                  "repaymentSchedule": {"periods": [
                    {"dueDate": [2026, 11, 3], "totalOutstandingForPeriod": 1000.00}
                  ]}
                }
                """;
    }

    private String transactionJson() {
        return """
                {
                  "principalPortion": 800.00,
                  "interestPortion": 200.00,
                  "feeChargesPortion": 0.00,
                  "penaltyChargesPortion": 0.00
                }
                """;
    }
}
