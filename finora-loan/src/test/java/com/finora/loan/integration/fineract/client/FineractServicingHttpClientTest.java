package com.finora.loan.integration.fineract.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finora.loan.domain.servicing.LoanServicingHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractServicingHttpClientTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void mapsOfficialSummaryAndDerivesDpdFromEarliestOutstandingPeriod() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRequestExecutor executor = mock(FineractRequestExecutor.class);
        doAnswer(invocation -> null).when(executor).authenticate(any());
        when(executor.execute(eq(FineractCallGroup.SERVICING), eq("read-servicing-loan"), any()))
                .thenAnswer(invocation -> ((FineractRequestExecutor.ExternalCall) invocation.getArgument(2)).execute());
        FineractServicingHttpClient client = new FineractServicingHttpClient(builder.build(), executor);
        server.expect(requestTo("http://fineract.test/loans/77?associations=repaymentSchedule"))
                .andRespond(withSuccess("""
                        {
                          "id":77,
                          "externalId":"LC-001",
                          "status":{"code":"loanStatusType.active"},
                          "summary":{"principalDisbursed":10000000,"principalPaid":1000000,
                            "principalOutstanding":9000000,"interestCharged":1200000,
                            "interestPaid":100000,"interestOutstanding":1100000,
                            "feeChargesOutstanding":0,"penaltyChargesOutstanding":10000,
                            "totalOutstanding":10110000,"totalOverdue":1010000},
                          "timeline":{"expectedMaturityDate":[2027,10,1]},
                          "repaymentSchedule":{"periods":[
                            {"dueDate":[2026,9,23],"totalOutstandingForPeriod":1010000},
                            {"dueDate":[2026,10,23],"totalOutstandingForPeriod":1000000}
                          ]}
                        }
                        """, MediaType.APPLICATION_JSON));

        var snapshot = client.read(77L, LocalDate.of(2026, 10, 3));

        assertThat(snapshot.daysPastDue()).isEqualTo(10);
        assertThat(snapshot.coreLoanId()).isEqualTo(77L);
        assertThat(snapshot.externalId()).isEqualTo("LC-001");
        assertThat(snapshot.overdueSince()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(snapshot.overdueAmount()).isEqualByComparingTo("1010000.00");
        assertThat(snapshot.nextDueDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        server.verify();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void readsPrincipalMovementsAndInstallmentsSkippingAccrualReversedAndDisbursementRow() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRequestExecutor executor = mock(FineractRequestExecutor.class);
        doAnswer(invocation -> null).when(executor).authenticate(any());
        when(executor.execute(eq(FineractCallGroup.SERVICING), eq("read-servicing-history"), any()))
                .thenAnswer(invocation -> ((FineractRequestExecutor.ExternalCall) invocation.getArgument(2)).execute());
        FineractServicingHttpClient client = new FineractServicingHttpClient(builder.build(), executor);
        server.expect(requestTo("http://fineract.test/loans/1020?associations=repaymentSchedule,transactions"))
                .andRespond(withSuccess("""
                        {
                          "id":1020,
                          "externalId":"LC-020",
                          "repaymentSchedule":{"periods":[
                            {"dueDate":[2026,5,19],"principalDisbursed":100000000},
                            {"period":1,"dueDate":[2026,6,19],"obligationsMetOnDate":[2026,6,19]},
                            {"period":2,"dueDate":[2026,7,19]}
                          ]},
                          "transactions":[
                            {"type":{"disbursement":true},"date":[2026,5,19],"amount":100000000,
                              "principalPortion":0,"manuallyReversed":false},
                            {"type":{"accrual":true},"date":[2026,5,19],"amount":7464000,"principalPortion":0},
                            {"type":{"repayment":true},"date":[2026,6,19],"amount":8955000,
                              "principalPortion":7830000,"manuallyReversed":false},
                            {"type":{"repayment":true},"date":[2026,6,20],"amount":100,
                              "principalPortion":100,"manuallyReversed":true}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        LoanServicingHistory history = client.readHistory(1020L);

        assertThat(history.externalId()).isEqualTo("LC-020");
        assertThat(history.movements()).extracting(LoanServicingHistory.PrincipalMovement::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100000000"), new BigDecimal("-7830000"));
        assertThat(history.installments()).containsExactly(
                new LoanServicingHistory.Installment(LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 19)),
                new LoanServicingHistory.Installment(LocalDate.of(2026, 7, 19), null));
        server.verify();
    }
}
