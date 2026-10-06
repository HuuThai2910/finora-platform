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
import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractRescheduleHttpClientTest {
    @Test
    void reconcilesExistingRequestByFinoraMarker() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRescheduleHttpClient client = client(builder, passThroughExecutor());
        server.expect(requestTo("http://fineract.test/rescheduleloans?loanId=77"))
                .andRespond(withSuccess("""
                        [{"id":91,"rescheduleReasonComment":"[FINORA:abc]",
                          "statusEnum":{"pendingApproval":true,"code":"pendingApproval"}}]
                        """, MediaType.APPLICATION_JSON));

        var result = client.findByLoanAndMarker(77L, "[FINORA:abc]");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().id()).isEqualTo(91L);
        assertThat(result.orElseThrow().approved()).isFalse();
        server.verify();
    }

    @Test
    void createsAndApprovesUsingFineractDateContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractRescheduleHttpClient client = client(builder, passThroughExecutor());
        server.expect(requestTo("http://fineract.test/rescheduleloans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"extraTerms\":2")))
                .andRespond(withSuccess("{\"resourceId\":91}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/rescheduleloans/91?command=approve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"approvedOnDate\":\"2026-10-04\"")))
                .andRespond(withSuccess("{\"resourceId\":91}", MediaType.APPLICATION_JSON));

        var result = client.create(new FineractRescheduleGateway.CreateRescheduleCommand(77L,
                LoanRescheduleType.TERM_EXTENSION, LocalDate.of(2026, 11, 1), null, 2,
                5L, "[FINORA:abc]", LocalDate.of(2026, 10, 4)));
        client.approve(result.id(), LocalDate.of(2026, 10, 4));

        assertThat(result.id()).isEqualTo(91L);
        server.verify();
    }

    private FineractRescheduleHttpClient client(RestClient.Builder builder, FineractRequestExecutor executor) {
        return new FineractRescheduleHttpClient(builder.build(), executor,
                new FineractBookingProperties(null, null, 1L, "en", "yyyy-MM-dd"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FineractRequestExecutor passThroughExecutor() {
        FineractRequestExecutor executor = mock(FineractRequestExecutor.class);
        doAnswer(invocation -> null).when(executor).authenticate(any());
        when(executor.execute(eq(FineractCallGroup.RESTRUCTURING), any(), any()))
                .thenAnswer(invocation -> ((FineractRequestExecutor.ExternalCall) invocation.getArgument(2)).execute());
        return executor;
    }
}
