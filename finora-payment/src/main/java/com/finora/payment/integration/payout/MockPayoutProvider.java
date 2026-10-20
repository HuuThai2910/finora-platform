package com.finora.payment.integration.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.config.PayoutProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Provider chi tiền giả lập (R4, D43) — không chuyển tiền thật. Nhận lệnh ngay, rồi sau {@code callbackDelay} gọi lại
 * endpoint callback của Payment bằng HTTP thật, body ký HMAC-SHA256, như một provider xử lý bất đồng bộ. Số tài khoản
 * kết thúc {@code 0000} mô phỏng tài khoản sai: provider vẫn nhận lệnh rồi báo thất bại qua callback.
 * <p>
 * Idempotent theo {@code withdrawalId}: gửi lại cùng mã trả đúng kết quả lần đầu (D42). Kết quả nằm trong bộ nhớ, nên sau
 * khi Payment khởi động lại tra cứu trả {@code NOT_FOUND} và Payment gửi lại lệnh với đúng mã đó.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finora.payment.payout.provider", havingValue = "mock", matchIfMissing = true)
public class MockPayoutProvider implements PayoutProvider, DisposableBean {

    static final String INVALID_ACCOUNT_SUFFIX = "0000";

    private final PayoutProperties.Mock settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RestClient callbackClient;
    private final Map<UUID, Status> outcomes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mock-payout-callback");
        thread.setDaemon(true);
        return thread;
    });

    public MockPayoutProvider(PayoutProperties properties, ObjectMapper objectMapper, Clock clock,
            RestClient.Builder builder) {
        this.settings = properties.mock();
        this.objectMapper = objectMapper;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.callbackClient = builder.requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public Submission submit(PayoutCommand command) {
        Status status = outcomes.computeIfAbsent(command.withdrawalId(), ignored -> decide(command));
        if (settings.callbackEnabled()) {
            scheduler.schedule(() -> sendCallback(command.withdrawalId(), status),
                    settings.callbackDelay().toMillis(), TimeUnit.MILLISECONDS);
        }
        return Submission.accepted(status.reference());
    }

    @Override
    public Status lookup(UUID withdrawalId) {
        Status status = outcomes.get(withdrawalId);
        return status != null ? status : new Status(Outcome.NOT_FOUND, null, null, null);
    }

    private static Status decide(PayoutCommand command) {
        String reference = "MOCKPAYOUT-" + command.withdrawalId();
        if (command.accountNumber().endsWith(INVALID_ACCOUNT_SUFFIX)) {
            return new Status(Outcome.FAILED, reference, "MOCK_ACCOUNT_INVALID",
                    "Tài khoản nhận không hợp lệ (provider giả lập)");
        }
        return new Status(Outcome.SUCCEEDED, reference, null, null);
    }

    private void sendCallback(UUID withdrawalId, Status status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("withdrawalId", withdrawalId.toString());
            payload.put("reference", status.reference());
            payload.put("status", status.outcome().name());
            payload.put("errorCode", status.errorCode());
            payload.put("errorMessage", status.errorMessage());
            payload.put("sentAt", clock.instant().toString());
            String body = objectMapper.writeValueAsString(payload);
            callbackClient.post().uri(settings.callbackUrl())
                    .header(PayoutSignature.HEADER, PayoutSignature.sign(body, settings.callbackSecret()))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        } catch (Exception exception) {
            // Mất callback là ca thật của provider: Payment tự tra cứu sau callbackTimeout.
            log.warn("Provider payout giả lập không gửi được callback: withdrawalId={}, error={}", withdrawalId,
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
