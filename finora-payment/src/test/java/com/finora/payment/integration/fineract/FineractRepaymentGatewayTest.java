package com.finora.payment.integration.fineract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finora.payment.config.PaymentFineractProperties;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractRepaymentGatewayTest {

    @Test
    void readsScheduledAmountFromOfficialRepaymentTemplate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRepaymentGateway gateway = gateway(builder);
        server.expect(requestTo("http://fineract.test/loans/77/transactions/template?command=repayment"))
                .andRespond(withSuccess("{\"total\":{\"amount\":1250000.00}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/77"))
                .andRespond(withSuccess("{\"summary\":{\"totalOverdue\":250000.00}}", MediaType.APPLICATION_JSON));

        ScheduledRepaymentCoreQuote quote = gateway.scheduledRepaymentQuote(77L);
        assertThat(quote.amount()).isEqualByComparingTo("1250000.00");
        assertThat(quote.overdueAmount()).isEqualByComparingTo("250000.00");
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

    private FineractRepaymentGateway gateway(RestClient.Builder builder) {
        PaymentFineractProperties properties = new PaymentFineractProperties(
                "http://fineract.test", "default", "mifos", "password", 1L,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new FineractRepaymentGateway(builder.build(), properties);
    }

    private String loanJson() {
        return """
                {
                  "transactions": [{"id": 501, "externalId": "FINORA-REPAY-abc", "reversed": false}],
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
