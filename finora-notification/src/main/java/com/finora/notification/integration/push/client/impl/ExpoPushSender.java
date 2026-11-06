package com.finora.notification.integration.push.client.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.client.PushSender;
import com.finora.notification.integration.push.contract.PushMessage;
import com.finora.notification.integration.push.contract.PushOutcome;
import com.finora.notification.integration.push.contract.PushReceipt;
import com.finora.notification.integration.push.contract.PushTicket;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gửi push thật qua Expo Push API ({@code POST /send}, {@code POST /getReceipts}), chỉ dùng khi
 * {@code NOTIFICATION_PUSH_PROVIDER=expo} (D62).
 * <p>
 * Phân loại lỗi theo tài liệu Expo: {@code DeviceNotRegistered} → thu hồi token; {@code MessageTooBig} → lỗi vĩnh viễn;
 * {@code MessageRateExceeded}, cấu hình APNs/FCM sai, HTTP 429/5xx, timeout, mất kết nối → thử lại có giới hạn. Expo không
 * có idempotency key cho lần gửi: timeout sau khi Expo đã nhận có thể làm người dùng nhận trùng push khi thử lại
 * (at-least-once); chấp nhận được vì push không làm đổi tiền hay trạng thái nghiệp vụ.
 * Không log token, tiêu đề hay thân push; chỉ log số thông điệp, độ trễ và lớp kết quả.
 * <p>
 * Mọi lời gọi Expo đi qua circuit breaker {@value #CIRCUIT_BREAKER_NAME} (cấu hình
 * {@code resilience4j.circuitbreaker.instances.expo-push}, cùng cách finora-loan): HTTP 5xx, timeout, mất kết nối được
 * tính là lỗi, HTTP 4xx thì không. Circuit mở thì không gọi Expo: lô gửi nhận {@link PushOutcome#RETRYABLE_FAILURE}
 * mã {@code EXPO_CIRCUIT_OPEN} và đọc receipt trả rỗng, đúng kết quả như khi Expo không với tới được, nên worker thử lại
 * theo backoff và giới hạn số lần như cũ.
 */
@Slf4j
public class ExpoPushSender implements PushSender {

    /** Tên circuit breaker và TimeLimiter trong {@code resilience4j.*.instances}. */
    static final String CIRCUIT_BREAKER_NAME = "expo-push";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    /**
     * @param restClient            đã cấu hình base URL, timeout và header xác thực ({@code PushSenderConfig})
     * @param circuitBreakerFactory tạo circuit breaker {@value #CIRCUIT_BREAKER_NAME} bọc từng lời gọi Expo
     */
    public ExpoPushSender(RestClient restClient, ObjectMapper objectMapper,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.circuitBreakerFactory = circuitBreakerFactory;
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
            String response = post("/send", messages.stream().map(ExpoPushSender::expoMessage).toList());
            return parseTickets(response, messages.size());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            result = "HTTP_" + status;
            PushOutcome outcome = status == 429 || status >= 500
                    ? PushOutcome.RETRYABLE_FAILURE : PushOutcome.PERMANENT_FAILURE;
            return Collections.nCopies(messages.size(), PushTicket.failed(outcome, "EXPO_HTTP_" + status));
        } catch (CallNotPermittedException exception) {
            // Circuit đang mở vì Expo vừa lỗi liên tục: không gọi, trả đúng kết quả "Expo không với tới được".
            result = "CIRCUIT_OPEN";
            return Collections.nCopies(messages.size(),
                    PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_CIRCUIT_OPEN"));
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
            String response = post("/getReceipts", Map.of("ids", ticketIds));
            return parseReceipts(response);
        } catch (RestClientResponseException exception) {
            result = "HTTP_" + exception.getStatusCode().value();
            return Map.of();
        } catch (CallNotPermittedException exception) {
            result = "CIRCUIT_OPEN";
            return Map.of();
        } catch (RestClientException exception) {
            result = "UNREACHABLE";
            return Map.of();
        } finally {
            log.info("Gọi Expo Push: operation=getReceipts, tickets={}, latencyMs={}, result={}",
                    ticketIds.size(), (System.nanoTime() - started) / 1_000_000, result);
        }
    }

    /**
     * POST JSON tới Expo qua circuit breaker {@value #CIRCUIT_BREAKER_NAME}, trả body thô. Lỗi được ném lại đúng loại để
     * nơi gọi phân loại như khi gọi thẳng; circuit mở thì ném {@link CallNotPermittedException} mà không gọi Expo.
     */
    private String post(String path, Object body) {
        return circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME).run(
                () -> restClient.post().uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class),
                throwable -> {
                    throw unchecked(throwable);
                });
    }

    /**
     * Lỗi unchecked giữ nguyên. Lỗi checked chỉ đến từ TimeLimiter của circuit breaker: {@code TimeoutException} khi
     * vượt {@code resilience4j.timelimiter.instances.expo-push.timeout-duration}, hoặc luồng worker bị ngắt khi đang chờ.
     * Cả hai được bọc thành {@link RestClientException} để xử lý như timeout/mất kết nối của HTTP client.
     */
    static RuntimeException unchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new RestClientException("Expo Push không phản hồi trong thời gian cho phép", throwable);
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
        JsonNode ticketNodes = root == null ? null : root.get("data");
        if (ticketNodes == null || !ticketNodes.isArray()) {
            // Lỗi cấp request (ví dụ quá nhiều thông điệp, sai project): không có ticket nào được tạo.
            String code = root != null && root.path("errors").isArray() && !root.path("errors").isEmpty()
                    ? limited(root.path("errors").get(0).path("code").asText("EXPO_REQUEST_ERROR"))
                    : "EXPO_RESPONSE_INVALID";
            return Collections.nCopies(expected, PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, code));
        }
        List<PushTicket> tickets = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            JsonNode ticket = ticketNodes.get(index);
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
        JsonNode receiptNodes = root == null ? null : root.get("data");
        if (receiptNodes == null || !receiptNodes.isObject()) return Map.of();
        Map<String, PushReceipt> receipts = new LinkedHashMap<>();
        receiptNodes.fields().forEachRemaining(entry -> {
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
            return objectMapper.readTree(response);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String limited(String code) {
        if (code == null || code.isBlank()) return "EXPO_ERROR";
        return code.length() <= 60 ? code : code.substring(0, 60);
    }
}
