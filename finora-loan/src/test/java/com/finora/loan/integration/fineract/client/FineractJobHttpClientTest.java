package com.finora.loan.integration.fineract.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.finora.loan.integration.fineract.contract.FineractJob;
import com.finora.loan.integration.fineract.contract.FineractJobRun;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractJobHttpClientTest {

    private MockRestServiceServer server;
    private FineractRequestExecutor executor;
    private FineractJobHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        server = MockRestServiceServer.bindTo(builder).build();
        executor = mock(FineractRequestExecutor.class);
        doAnswer(invocation -> null).when(executor).authenticate(any());
        client = new FineractJobHttpClient(builder.build(), executor);
    }

    @Test
    void readsJobsWithLastRunInIsoInstantAsSerializedByFineract115() {
        server.expect(requestTo("http://fineract.test/jobs")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"jobId":12,"displayName":"Update Loan Arrears Ageing","shortName":"LA_ARAG",
                           "active":true,"currentlyRunning":false,
                           "lastRunHistory":{"version":3,"jobRunStartTime":"2026-10-16T17:01:00Z",
                             "jobRunEndTime":"2026-10-16T17:01:04.512Z","status":"success","triggerType":"cron"}},
                          {"jobId":7,"displayName":"Recalculate Interest For Loans","shortName":"LA_RINT",
                           "active":false,"currentlyRunning":true},
                          {"jobId":"x","shortName":"BROKEN"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<FineractJob> jobs = client.listJobs();

        assertThat(jobs).hasSize(2);
        FineractJob ageing = jobs.get(0);
        assertThat(ageing.jobId()).isEqualTo(12L);
        assertThat(ageing.shortName()).isEqualTo("LA_ARAG");
        assertThat(ageing.active()).isTrue();
        assertThat(ageing.lastRun().succeeded()).isTrue();
        assertThat(ageing.lastRun().completedAt()).isEqualTo(Instant.parse("2026-10-16T17:01:04.512Z"));
        FineractJob interest = jobs.get(1);
        assertThat(interest.lastRun()).isNull();
        assertThat(interest.active()).isFalse();
        assertThat(interest.currentlyRunning()).isTrue();
        server.verify();
    }

    @Test
    void historyIsRequestedNewestFirstAndKeepsFailedRuns() {
        server.expect(requestTo(
                        "http://fineract.test/jobs/7/runhistory?offset=0&limit=50&orderBy=id&sortOrder=DESC"))
                .andRespond(withSuccess("""
                        {"totalFilteredRecords":3,"pageItems":[
                          {"id":30,"version":30,"jobRunStartTime":"2026-10-17T17:01:00Z",
                           "jobRunEndTime":"2026-10-17T17:02:00Z","status":"failed","jobRunErrorMessage":"loan 25"},
                          {"id":29,"version":29,"jobRunStartTime":"2026-10-16T17:01:00Z",
                           "jobRunEndTime":"2026-10-16T17:01:30+07:00","status":"success"},
                          {"id":28,"version":28}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<FineractJobRun> runs = client.recentRuns(7, 50);

        assertThat(runs).extracting(FineractJobRun::status).containsExactly("failed", "success");
        // Offset +07:00 của bản Fineract cũ vẫn đọc đúng thời điểm tuyệt đối.
        assertThat(runs.get(1).completedAt()).isEqualTo(Instant.parse("2026-10-16T10:01:30Z"));
        server.verify();
    }

    @Test
    void transportErrorsAreClassifiedLikeOtherFineractAdapters() {
        FineractIntegrationException mapped = new FineractIntegrationException(
                "FINERACT_TEMPORARY_ERROR", "Fineract lỗi tạm thời", true, null);
        when(executor.mapException(any())).thenReturn(mapped);
        server.expect(requestTo("http://fineract.test/jobs")).andRespond(withServerError());

        assertThatThrownBy(client::listJobs).isSameAs(mapped);
    }

    @Test
    void nonArrayResponseIsInvalid() {
        server.expect(requestTo("http://fineract.test/jobs"))
                .andRespond(withSuccess("{\"error\":\"unexpected\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::listJobs)
                .isInstanceOf(FineractIntegrationException.class)
                .extracting(exception -> ((FineractIntegrationException) exception).getCode())
                .isEqualTo("FINERACT_RESPONSE_INVALID");
    }

    @Test
    void instantParsingAcceptsIsoInstantOffsetAndEpochMillisOnly() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        assertThat(FineractJobHttpClient.instant(json.textNode("2026-10-17T00:01:00Z")))
                .isEqualTo(Instant.parse("2026-10-17T00:01:00Z"));
        assertThat(FineractJobHttpClient.instant(json.textNode("2026-10-17T07:01:00+07:00")))
                .isEqualTo(Instant.parse("2026-10-17T00:01:00Z"));
        assertThat(FineractJobHttpClient.instant(json.numberNode(1_760_659_260_000L)))
                .isEqualTo(Instant.ofEpochMilli(1_760_659_260_000L));
        assertThat(FineractJobHttpClient.instant(json.textNode("Oct 17, 2026 12:01:00 AM"))).isNull();
        assertThat(FineractJobHttpClient.instant(json.nullNode())).isNull();
    }
}
