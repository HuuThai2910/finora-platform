package com.finora.notification.integration.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.config.PushProviderType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gửi push thật qua Expo Push API ({@code POST /send}, {@code POST /getReceipts}), chỉ dùng khi
 * {@code NOTIFICATION_PUSH_PROVIDER=expo} (D62).
 * <p>
 * Phân loại lỗi theo tài liệu Expo: {@code DeviceNotRegistered} → thu hồi token; {@code MessageTooBig} → lỗi vĩnh viễn;
 * {@code MessageRateExceeded}, cấu hình APNs/FCM sai, HTTP 429/5xx, timeout, mất kết nối → thử lại có giới hạn. Expo không
 * có idempotency key cho lần gửi: timeout sau khi Expo đã nhận có thể làm người dùng nhận trùng push khi thử lại
 * (at-least-once); chấp nhận được vì push không làm đổi tiền hay trạng thái nghiệp vụ.
 * Không log token, tiêu đề hay thân push; chỉ log số thông điệp, độ trễ và lớp kết quả.
 */
@Slf4j
public class ExpoPushSender implements PushSender {

    private final RestClient client;
    private final ObjectMapper mapper;

    /** @param client đã cấu hình base URL, timeout và header xác thực ({@code PushSenderConfig}) */
    public ExpoPushSender(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public PushProviderType provider() {
        return PushProviderType.EXPO;
    }

    @Override
    public List<PushTicket> send(List<PushMessage> messages) {
        if (messages.isEmpty()) return List.of();
        long started = System.nanoTime();
        String result = "OK";
        try {
            String response = client.post().uri("/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(messages.stream().map(ExpoPushSender::expoMessage).toList())
                    .retrieve()
                    .body(String.class);
            return parseTickets(response, messages.size());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            result = "HTTP_" + status;
            PushOutcome outcome = status == 429 || status >= 500
                    ? PushOutcome.RETRYABLE_FAILURE : PushOutcome.PERMANENT_FAILURE;
            return Collections.nCopies(messages.size(), PushTicket.failed(outcome, "EXPO_HTTP_" + status));
        } catch (RestClientException exception) {
            // Timeout/mất kết nối: không biết Expo đã nhận hay chưa, nên thử lại (at-least-once).
            result = "UNREACHABLE";
            return Collections.nCopies(messages.size(),
                    PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_UNREACHABLE"));
        } finally {
            log.info("Gọi Expo Push: operation=send, messages={}, latencyMs={}, result={}",
                    messages.size(), (System.nanoTime() - started) / 1_000_000, result);
        }
    }

    @Override
    public Map<String, PushReceipt> receipts(List<String> ticketIds) {
        if (ticketIds.isEmpty()) return Map.of();
        long started = System.nanoTime();
        String result = "OK";
        try {
            String response = client.post().uri("/getReceipts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ids", ticketIds))
                    .retrieve()
                    .body(String.class);
            return parseReceipts(response);
        } catch (RestClientResponseException exception) {
            result = "HTTP_" + exception.getStatusCode().value();
            return Map.of();
        } catch (RestClientException exception) {
            result = "UNREACHABLE";
            return Map.of();
        } finally {
            log.info("Gọi Expo Push: operation=getReceipts, tickets={}, latencyMs={}, result={}",
                    ticketIds.size(), (System.nanoTime() - started) / 1_000_000, result);
        }
    }

    /** Định dạng thông điệp Expo; {@code channelId} khớp kênh Android "default" mà app tạo lúc đăng ký. */
    static Map<String, Object> expoMessage(PushMessage message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("to", message.token());
        value.put("title", message.title());
        value.put("body", message.body());
        value.put("data", message.data());
        value.put("sound", "default");
        value.put("channelId", "default");
        value.put("priority", "default");
        value.put("ttl", message.ttl().toSeconds());
        return value;
    }

    private List<PushTicket> parseTickets(String response, int expected) {
        JsonNode root = read(response);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            // Lỗi cấp request (ví dụ quá nhiều thông điệp, sai project): không có ticket nào được tạo.
            String code = root != null && root.path("errors").isArray() && !root.path("errors").isEmpty()
                    ? limited(root.path("errors").get(0).path("code").asText("EXPO_REQUEST_ERROR"))
                    : "EXPO_RESPONSE_INVALID";
            return Collections.nCopies(expected, PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, code));
        }
        List<PushTicket> tickets = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            JsonNode ticket = data.get(index);
            if (ticket == null) {
                tickets.add(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_TICKET_MISSING"));
            } else if ("ok".equals(ticket.path("status").asText()) && ticket.hasNonNull("id")) {
                tickets.add(PushTicket.accepted(ticket.get("id").asText()));
            } else {
                String code = limited(ticket.path("details").path("error").asText("EXPO_TICKET_ERROR"));
                tickets.add(PushTicket.failed(classifyTicketError(code), code));
            }
        }
        return tickets;
    }

    private Map<String, PushReceipt> parseReceipts(String response) {
        JsonNode root = read(response);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) return Map.of();
        Map<String, PushReceipt> receipts = new LinkedHashMap<>();
        data.fields().forEachRemaining(entry -> {
            JsonNode receipt = entry.getValue();
            if ("ok".equals(receipt.path("status").asText())) {
                receipts.put(entry.getKey(), new PushReceipt(PushOutcome.ACCEPTED, null));
            } else {
                String code = limited(receipt.path("details").path("error").asText("EXPO_RECEIPT_ERROR"));
                receipts.put(entry.getKey(), new PushReceipt(classifyReceiptError(code), code));
            }
        });
        return receipts;
    }

    static PushOutcome classifyTicketError(String code) {
        return switch (code) {
            case "DeviceNotRegistered" -> PushOutcome.DEVICE_NOT_REGISTERED;
            case "MessageTooBig" -> PushOutcome.PERMANENT_FAILURE;
            // Vượt tần suất hoặc credential APNs/FCM của project đang sai: thử lại có giới hạn, hết lượt thì DEAD để
            // vận hành sửa cấu hình rồi gửi lại.
            default -> PushOutcome.RETRYABLE_FAILURE;
        };
    }

    /** Receipt lỗi là kết luận cuối của APNs/FCM cho ticket đó, trừ vượt tần suất (Expo khuyên gửi lại có lùi dần). */
    static PushOutcome classifyReceiptError(String code) {
        return switch (code) {
            case "DeviceNotRegistered" -> PushOutcome.DEVICE_NOT_REGISTERED;
            case "MessageRateExceeded" -> PushOutcome.RETRYABLE_FAILURE;
            default -> PushOutcome.PERMANENT_FAILURE;
        };
    }

    private JsonNode read(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            return mapper.readTree(response);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String limited(String code) {
        if (code == null || code.isBlank()) return "EXPO_ERROR";
        return code.length() <= 60 ? code : code.substring(0, 60);
    }
}
