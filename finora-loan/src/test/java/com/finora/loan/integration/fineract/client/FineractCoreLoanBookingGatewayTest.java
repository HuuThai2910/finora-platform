package com.finora.loan.integration.fineract.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finora.loan.config.FineractBookingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractCoreLoanBookingGatewayTest {

    @Test
    void createsV2ClientAndLoanUsingTenantRequiredFieldsThenDisburses() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractCoreLoanBookingGateway gateway = new FineractCoreLoanBookingGateway(
                builder.build(), passThroughExecutor(),
                new FineractBookingProperties(1L, 1L, 1L, "en", "yyyy-MM-dd"),
                Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC));

        server.expect(requestTo("http://fineract.test/clients?externalId=FINORA-BORROWER-BORROWER-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/clients"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"officeId\":1"),
                        Matchers.containsString("\"legalFormId\":1"),
                        Matchers.containsString("\"externalId\":\"FINORA-BORROWER-BORROWER-1\""))))
                .andRespond(withSuccess("{\"resourceId\":2}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans?externalId=LC-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"interestCalculationPeriodType\":1"),
                        Matchers.containsString("\"transactionProcessingStrategyCode\":\"advanced-payment-allocation-strategy\""))))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3"))
                .andRespond(withSuccess("{\"status\":{\"pendingApproval\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=approve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3"))
                .andRespond(withSuccess("{\"status\":{\"approved\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=disburse"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("\"paymentTypeId\":1")))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));

        var result = gateway.bookAndDisburse(new CoreLoanBookingGateway.CoreLoanBookingCommand(
                1L, "LC-1", "BORROWER-1", 2L, new BigDecimal("50000000"), 6,
                new BigDecimal("15"), "FINORA-FINERACT-V2", LocalDate.of(2026, 10, 4), "PAY-1"));

        assertThat(result.fineractLoanId()).isEqualTo(3L);
        server.verify();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FineractRequestExecutor passThroughExecutor() {
        FineractRequestExecutor executor = mock(FineractRequestExecutor.class);
        doAnswer(invocation -> null).when(executor).authenticate(any());
        when(executor.execute(eq(FineractCallGroup.BOOKING), any(), any()))
                .thenAnswer(invocation -> ((FineractRequestExecutor.ExternalCall) invocation.getArgument(2)).execute());
        return executor;
    }
}
