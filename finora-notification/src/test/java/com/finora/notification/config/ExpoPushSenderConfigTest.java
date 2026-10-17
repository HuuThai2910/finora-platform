package com.finora.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.integration.push.ExpoPushSender;
import com.finora.notification.integration.push.PushMessage;
import com.finora.notification.integration.push.PushOutcome;
import com.finora.notification.integration.push.PushReceipt;
import com.finora.notification.integration.push.PushTicket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Adapter Expo Push (D62) với mock HTTP server, không gọi mạng: định dạng request, header xác thực, phân loại ticket,
 * lỗi HTTP và receipt theo tài liệu Expo.
 */
class ExpoPushSenderConfigTest {

    private static final String BASE = "https://exp.example.test/--/api/v2/push";
    private static final String TOKEN = "ExponentPushToken[FAKE-test-token-0001]";

    @Test
    void sendsExpoMessageFormatWithoutAuthorizationWhenNoAccessTokenIsConfigured() {
        Harness harness = harness(null);
        harness.server.expect(requestTo(BASE + "/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$[0].to").value(TOKEN))
                .andExpect(jsonPath("$[0].title").value("Trả nợ"))
                .andExpect(jsonPath("$[0].body").value("Khoản trả nợ của bạn đã được ghi nhận."))
                .andExpect(jsonPath("$[0].data.notificationId").value("n-1"))
                .andExpect(jsonPath("$[0].channelId").value("default"))
                .andExpect(jsonPath("$[0].ttl").value(86400))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-1\"}]}",
                        MediaType.APPLICATION_JSON));

        List<PushTicket> tickets = harness.sender.send(List.of(message(1)));

        assertThat(tickets).containsExactly(PushTicket.accepted("ticket-1"));
        harness.server.verify();
    }

    @Test
    void sendsBearerAccessTokenWhenEnhancedSecurityIsEnabled() {
        Harness harness = harness("expo-access-token-for-test");
        harness.server.expect(requestTo(BASE + "/send"))
                .andExpect(header("Authorization", "Bearer expo-access-token-for-test"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-1\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(harness.sender.send(List.of(message(1)))).hasSize(1);
        harness.server.verify();
    }

    @Test
    void mapsEachTicketToItsOwnOutcomeInRequestOrder() {
        Harness harness = harness(null);
        harness.server.expect(requestTo(BASE + "/send")).andRespond(withSuccess("""
                {"data":[
                  {"status":"ok","id":"ticket-1"},
                  {"status":"error","message":"...","details":{"error":"DeviceNotRegistered"}},
                  {"status":"error","message":"...","details":{"error":"MessageTooBig"}},
                  {"status":"error","message":"...","details":{"error":"MessageRateExceeded"}}
                ]}""", MediaType.APPLICATION_JSON));

        List<PushTicket> tickets = harness.sender.send(List.of(message(1), message(2), message(3), message(4)));

        assertThat(tickets).extracting(PushTicket::outcome).containsExactly(PushOutcome.ACCEPTED,
                PushOutcome.DEVICE_NOT_REGISTERED, PushOutcome.PERMANENT_FAILURE, PushOutcome.RETRYABLE_FAILURE);
        assertThat(tickets.get(1).errorCode()).isEqualTo("DeviceNotRegistered");
    }

    @Test
    void serverErrorAndRateLimitAreRetryableButBadRequestIsNot() {
        Harness unavailable = harness(null);
        unavailable.server.expect(requestTo(BASE + "/send")).andRespond(withServerError());
        assertThat(unavailable.sender.send(List.of(message(1), message(2))))
                .extracting(PushTicket::outcome).containsOnly(PushOutcome.RETRYABLE_FAILURE);

        Harness throttled = harness(null);
        throttled.server.expect(requestTo(BASE + "/send")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(throttled.sender.send(List.of(message(1))).get(0).errorCode()).isEqualTo("EXPO_HTTP_429");

        Harness rejected = harness(null);
        rejected.server.expect(requestTo(BASE + "/send")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(rejected.sender.send(List.of(message(1))).get(0).outcome())
                .isEqualTo(PushOutcome.PERMANENT_FAILURE);
    }

    @Test
    void requestLevelErrorWithoutTicketsIsRetryable() {
        Harness harness = harness(null);
        harness.server.expect(requestTo(BASE + "/send")).andRespond(withSuccess(
                "{\"errors\":[{\"code\":\"PUSH_TOO_MANY_EXPERIENCE_IDS\",\"message\":\"...\"}]}",
                MediaType.APPLICATION_JSON));

        PushTicket ticket = harness.sender.send(List.of(message(1))).get(0);

        assertThat(ticket.outcome()).isEqualTo(PushOutcome.RETRYABLE_FAILURE);
        assertThat(ticket.errorCode()).isEqualTo("PUSH_TOO_MANY_EXPERIENCE_IDS");
    }

    @Test
    void receiptsConfirmRevokeOrStayPending() {
        Harness harness = harness(null);
        harness.server.expect(requestTo(BASE + "/getReceipts"))
                .andExpect(jsonPath("$.ids[0]").value("ticket-1"))
                .andRespond(withSuccess("""
                        {"data":{
                          "ticket-1":{"status":"ok"},
                          "ticket-2":{"status":"error","details":{"error":"DeviceNotRegistered"}},
                          "ticket-3":{"status":"error","details":{"error":"MessageRateExceeded"}}
                        }}""", MediaType.APPLICATION_JSON));

        Map<String, PushReceipt> receipts = harness.sender.receipts(
                List.of("ticket-1", "ticket-2", "ticket-3", "ticket-4"));

        assertThat(receipts.get("ticket-1").outcome()).isEqualTo(PushOutcome.ACCEPTED);
        assertThat(receipts.get("ticket-2").outcome()).isEqualTo(PushOutcome.DEVICE_NOT_REGISTERED);
        assertThat(receipts.get("ticket-3").outcome()).isEqualTo(PushOutcome.RETRYABLE_FAILURE);
        // Chưa có receipt: không có trong map, worker hỏi lại sau.
        assertThat(receipts).doesNotContainKey("ticket-4");
    }

    @Test
    void receiptCallFailureReturnsNothingSoTheWorkerRetriesLater() {
        Harness harness = harness(null);
        harness.server.expect(requestTo(BASE + "/getReceipts")).andRespond(withServerError());

        assertThat(harness.sender.receipts(List.of("ticket-1"))).isEmpty();
    }

    private static PushMessage message(int index) {
        return new PushMessage(index, TOKEN, "tokenref" + index, "Trả nợ", "Khoản trả nợ của bạn đã được ghi nhận.",
                Map.of("notificationId", "n-" + index, "type", "REPAYMENT_RECORDED", "category", "REPAYMENT"),
                Duration.ofHours(24));
    }

    private static Harness harness(String accessToken) {
        RestClient.Builder builder = PushSenderConfig.applyExpoDefaults(RestClient.builder(),
                new NotificationProperties.Expo(BASE, accessToken, null, null));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(server, new ExpoPushSender(builder.build(), new ObjectMapper()));
    }

    private record Harness(MockRestServiceServer server, ExpoPushSender sender) {}
}
